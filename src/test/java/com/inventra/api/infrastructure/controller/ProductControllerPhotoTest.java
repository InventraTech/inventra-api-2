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
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.inventra.api.core.domain.product.Product;
import com.inventra.api.core.domain.unit.Unit;
import com.inventra.api.core.service.product.ProductUseCase;
import com.inventra.api.infrastructure.client.openfoodfacts.OpenFoodFactsClient;
import com.inventra.api.infrastructure.exception.ImageStorageException;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;

@WebMvcTest(ProductController.class)
@AutoConfigureMockMvc(addFilters = false)
class ProductControllerPhotoTest {

    private static final String PHOTO_URL =
            "https://res.cloudinary.com/demo/image/upload/v1/inventra/products/1.jpg";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ProductUseCase useCase;
    @MockitoBean private OpenFoodFactsClient openFoodFactsClient;

    private static MockMultipartFile jpegPart() {
        return new MockMultipartFile("file", "foto.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});
    }

    private static Product productWithPhoto() {
        Unit unit = Unit.builder().id(1).symbol("L").description("Litro").build();
        return Product.builder().id(1).name("Coca-Cola").unit(unit).active(true).photoUrl(PHOTO_URL).build();
    }

    private static Product productWithoutPhoto() {
        Unit unit = Unit.builder().id(1).symbol("L").description("Litro").build();
        return Product.builder().id(1).name("Coca-Cola").unit(unit).active(true).build();
    }

    @Test
    void updatePhotoReturnsProductWithNewUrl() throws Exception {
        when(useCase.updatePhoto(eq(1), any())).thenReturn(productWithPhoto());

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/products/1/photo").file(jpegPart()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photoUrl").value(PHOTO_URL));
    }

    @Test
    void updatePhotoReturns400WhenImageIsInvalid() throws Exception {
        when(useCase.updatePhoto(eq(1), any()))
                .thenThrow(new IllegalArgumentException("Formato de imagem não suportado."));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/products/1/photo").file(jpegPart()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatePhotoReturns404WhenProductDoesNotExist() throws Exception {
        when(useCase.updatePhoto(eq(1), any()))
                .thenThrow(new ResourceNotFoundException("Produto não encontrado."));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/products/1/photo").file(jpegPart()))
                .andExpect(status().isNotFound());
    }

    @Test
    void updatePhotoReturns502WhenStorageFails() throws Exception {
        when(useCase.updatePhoto(eq(1), any()))
                .thenThrow(new ImageStorageException("Falha no Cloudinary", new IOException()));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/products/1/photo").file(jpegPart()))
                .andExpect(status().isBadGateway());
    }

    @Test
    void updatePhotoReturns400WhenFilePartIsMissing() throws Exception {
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/products/1/photo"))
                .andExpect(status().isBadRequest());
    }

    // 413 e "part ausente" são respondidos pelo advice de ProblemDetail do Spring Boot
    // (spring.mvc.problemdetails.enabled=true), que roda antes do GlobalExceptionHandler.
    @Test
    void updatePhotoReturns413WhenUploadIsTooLarge() throws Exception {
        when(useCase.updatePhoto(eq(1), any()))
                .thenThrow(new MaxUploadSizeExceededException(5_242_880L));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/products/1/photo").file(jpegPart()))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void updatePhotoReturns400WhenFileBytesCannotBeRead() throws Exception {
        MockMultipartFile broken = new MockMultipartFile("file", "foto.jpg", "image/jpeg", new byte[]{0}) {
            @Override
            public byte[] getBytes() throws IOException {
                throw new IOException("temp file gone");
            }
        };

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/products/1/photo").file(broken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void removePhotoReturnsProductWithoutUrl() throws Exception {
        when(useCase.removePhoto(1)).thenReturn(productWithoutPhoto());

        mockMvc.perform(delete("/api/products/1/photo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photoUrl").doesNotExist());
    }
}
