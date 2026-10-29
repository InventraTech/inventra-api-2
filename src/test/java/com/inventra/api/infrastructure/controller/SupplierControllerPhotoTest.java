package com.inventra.api.infrastructure.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.inventra.api.core.domain.supplier.Supplier;
import com.inventra.api.core.service.supplier.SupplierUseCase;
import com.inventra.api.infrastructure.exception.ImageStorageException;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;

@WebMvcTest(SupplierController.class)
@AutoConfigureMockMvc(addFilters = false)
class SupplierControllerPhotoTest {

    private static final String PHOTO_URL =
            "https://res.cloudinary.com/demo/image/upload/v1/inventra/suppliers/1.jpg";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private SupplierUseCase useCase;

    private static MockMultipartFile jpegPart() {
        return new MockMultipartFile("file", "foto.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});
    }

    private static Supplier supplier(String photoUrl) {
        return Supplier.builder().id(1).legalName("Distribuidora Ltda").cnpj("12.345.678/0001-90")
                .active(true).photoUrl(photoUrl).build();
    }

    @Test
    void updatePhotoReturnsSupplierWithNewUrl() throws Exception {
        when(useCase.updatePhoto(eq(1), any())).thenReturn(supplier(PHOTO_URL));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/suppliers/1/photo").file(jpegPart()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photoUrl").value(PHOTO_URL));
    }

    @Test
    void updatePhotoReturns400WhenImageIsInvalid() throws Exception {
        when(useCase.updatePhoto(eq(1), any()))
                .thenThrow(new IllegalArgumentException("Formato de imagem não suportado."));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/suppliers/1/photo").file(jpegPart()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatePhotoReturns404WhenSupplierDoesNotExist() throws Exception {
        when(useCase.updatePhoto(eq(1), any()))
                .thenThrow(new ResourceNotFoundException("Fornecedor não encontrado."));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/suppliers/1/photo").file(jpegPart()))
                .andExpect(status().isNotFound());
    }

    @Test
    void updatePhotoReturns502WhenStorageFails() throws Exception {
        when(useCase.updatePhoto(eq(1), any()))
                .thenThrow(new ImageStorageException("Falha no Cloudinary", new IOException()));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/suppliers/1/photo").file(jpegPart()))
                .andExpect(status().isBadGateway());
    }

    @Test
    void updatePhotoReturns400WhenFilePartIsMissing() throws Exception {
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/suppliers/1/photo"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void removePhotoReturnsSupplierWithoutUrl() throws Exception {
        when(useCase.removePhoto(1)).thenReturn(supplier(null));

        mockMvc.perform(delete("/api/suppliers/1/photo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photoUrl").doesNotExist());
    }
}
