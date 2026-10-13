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
}
