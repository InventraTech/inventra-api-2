package com.inventra.api.infrastructure.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.access.AccessDeniedException;
import com.inventra.api.core.service.ranking.*;
import com.inventra.api.infrastructure.exception.RankingUnavailableException;

@WebMvcTest(RankingController.class)
@AutoConfigureMockMvc(addFilters = false)
class RankingControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean RankingService service;

    @Test void returnsTopWithDefaultLimitAndDateScore() throws Exception {
        when(service.top(1, 10)).thenReturn(List.of(new RankingEntry(1, 8, LocalDate.ofEpochDay(20000), 20000)));
        mvc.perform(get("/ranking").param("kitchenId", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].position").value(1))
                .andExpect(jsonPath("$[0].batchId").value(8))
                .andExpect(jsonPath("$[0].score").value(20000))
                .andExpect(jsonPath("$[0].expirationDate").value("2024-10-04"));
    }

    @Test void acceptsExplicitLimit() throws Exception {
        when(service.top(1, 2)).thenReturn(List.of());
        mvc.perform(get("/ranking").param("kitchenId", "1").param("limit", "2"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(service).top(1, 2);
    }

    @Test void rejectsMissingAndMalformedParameters() throws Exception {
        mvc.perform(get("/ranking")).andExpect(status().isBadRequest());
        mvc.perform(get("/ranking").param("kitchenId", "abc")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void forbiddenKitchenReturns403() throws Exception {
        when(service.top(2, 10)).thenThrow(new AccessDeniedException("Negado"));
        mvc.perform(get("/ranking").param("kitchenId", "2")).andExpect(status().isForbidden());
    }

    @Test void invalidLimitReturns400() throws Exception {
        when(service.top(1, 101)).thenThrow(new IllegalArgumentException("limit inválido"));
        mvc.perform(get("/ranking").param("kitchenId", "1").param("limit", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test void outageReturns503() throws Exception {
        when(service.top(1, 10)).thenThrow(new RankingUnavailableException(new RuntimeException("offline")));
        mvc.perform(get("/ranking").param("kitchenId", "1")).andExpect(status().isServiceUnavailable());
    }
}
