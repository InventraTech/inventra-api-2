package com.inventra.api.infrastructure.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.inventra.api.core.service.productqueue.ProductRegistrationService;
import com.inventra.api.core.service.productqueue.model.BarcodeRegistrationRequest;
import com.inventra.api.core.service.productqueue.model.ProductRegistrationJob;
import com.inventra.api.core.service.productqueue.model.ProductRegistrationResponse;
import com.inventra.api.infrastructure.exception.QueueServiceException;

@WebMvcTest(ProductRegistrationController.class)
@AutoConfigureMockMvc(addFilters = false)
class ProductRegistrationControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private ProductRegistrationService service;
    private static final String URL = "/api/products/barcode-registrations";
    private static final String REQUEST = """
            {"name":"Arroz","unitId":1,"barcode":"7891234567890"}
            """;

    @Test void acceptsRegistrationAndProvidesStatusLocation() throws Exception {
        var job = ProductRegistrationJob.queued(UUID.randomUUID(),
                new BarcodeRegistrationRequest("Arroz", null, null, 1, "7891234567890", null));
        when(service.enqueue(any())).thenReturn(ProductRegistrationResponse.from(job));
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isAccepted()).andExpect(header().string("Location", URL + "/" + job.eventId()))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test void rejectsMissingNameUnitAndInvalidBarcodeBeforeEnqueue() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":" ","unitId":-1,"barcode":"abc"}
                """))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.length()").value(3));
        verifyNoInteractions(service);
    }

    @Test void exposesCompletedResult() throws Exception {
        var job = ProductRegistrationJob.queued(UUID.randomUUID(),
                new BarcodeRegistrationRequest("Arroz", null, null, 1, "7891234567890", null)).processing().completed(12);
        when(service.find(job.eventId())).thenReturn(ProductRegistrationResponse.from(job));
        mvc.perform(get(URL + "/" + job.eventId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(12)).andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test void malformedJsonReturnsBadRequest() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void invalidEventIdReturnsBadRequest() throws Exception {
        mvc.perform(get(URL + "/invalid-id")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void deniesReadingAnotherUsersJob() throws Exception {
        when(service.find(any())).thenThrow(new AccessDeniedException("Negado"));
        mvc.perform(get(URL + "/" + UUID.randomUUID())).andExpect(status().isForbidden());
    }

    @Test void redisFailureReturns503WithoutClaimingAcceptance() throws Exception {
        when(service.enqueue(any())).thenThrow(new RedisConnectionFailureException("Detalhe privado"));
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Serviço de processamento indisponível. Tente novamente em instantes."));
    }

    @Test void queueInvariantFailureReturns503WithoutInternalDetails() throws Exception {
        when(service.enqueue(any())).thenThrow(new QueueServiceException("Confirmação privada"));
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Serviço de processamento indisponível. Tente novamente em instantes."));
    }
}
