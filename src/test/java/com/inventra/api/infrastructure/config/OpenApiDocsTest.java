package com.inventra.api.infrastructure.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.inventra.api.infrastructure.client.openfoodfacts.OpenFoodFactsClient;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiDocsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OpenFoodFactsClient openFoodFactsClient;

    @Test
    void apiDocsAreAvailableWithoutAuthenticationAndDescribeEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Inventra API"))
                .andExpect(jsonPath("$.components.schemas.ProblemDetail").exists())
                .andExpect(jsonPath("$.paths['/api/categories'].post.summary").value("Cria uma categoria"))
                .andExpect(jsonPath("$.paths['/api/categories'].post.tags[0]").value("Categorias"))
                .andExpect(jsonPath("$.paths['/api/categories'].post.responses['400']").exists())
                .andExpect(jsonPath("$.paths['/api/categories'].post.responses['403']").exists())
                .andExpect(jsonPath("$.paths['/api/categories/{id}'].get.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['401']").exists())
                .andExpect(jsonPath("$.components.schemas.CreateCategoryRequest.properties.name.example").value("Grãos"));
    }

    // springdoc assume 200 para ResponseEntity; os @ApiResponse dos controllers documentam o código real.
    @Test
    void apiDocsDescribeActualSuccessStatusCodes() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/categories'].post.responses['201'].content").exists())
                .andExpect(jsonPath("$.paths['/api/categories'].post.responses['200']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/categories/{id}'].delete.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/categories/{id}'].delete.responses['200']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/products/barcode-registrations'].post.responses['202']").exists())
                .andExpect(jsonPath("$.paths['/api/products/barcode-registrations'].post.responses['200']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/products/barcode-registrations'].post.tags[0]").value("Produtos"));
    }
}
