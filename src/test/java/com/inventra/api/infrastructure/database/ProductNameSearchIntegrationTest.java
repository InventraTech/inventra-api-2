package com.inventra.api.infrastructure.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;

// Busca de produto por nome (GET /api/products?name e GET /api/kitchens/{id}/stock?name) contra o Postgres:
// trecho do nome, sem diferenciar maiúscula nem acento, nos dois sentidos. O H2 dos outros testes não tem o
// translate() que a query usa.
class ProductNameSearchIntegrationTest extends PostgresIntegrationTest {

    private int sugar;
    private int rice;
    private int bean;

    private void createProducts() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        int unitId = createdId("/api/units", "{\"symbol\":\"u%s\",\"description\":\"Unidade %s\"}".formatted(suffix, suffix));
        int supplierId = createdId("/api/suppliers", "{\"legalName\":\"Fornecedor Busca\",\"cnpj\":\"%s\"}".formatted(
                ThreadLocalRandom.current().nextLong(10_000_000_000_000L, 99_999_999_999_999L)));
        sugar = createProduct("Açúcar Cristal", unitId, supplierId);
        rice = createProduct("Arroz Tipo 1 Branco", unitId, supplierId);
        bean = createProduct("Feijão Carioca", unitId, supplierId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"acucar", "ACUCAR", "Açúcar", "AÇÚCAR", "cristal", "ucar c"})
    void catalogSearchIgnoresCaseAndAccents(String term) throws Exception {
        createProducts();

        assertThat(catalogIds(term)).contains(sugar).doesNotContain(rice, bean);
    }

    @Test
    void catalogSearchFindsAccentedNamesFromUnaccentedAndAccentedTermsAlike() throws Exception {
        createProducts();

        assertThat(catalogIds("feijao")).contains(bean).doesNotContain(sugar, rice);
        assertThat(catalogIds("feijão")).contains(bean).doesNotContain(sugar, rice);
        assertThat(catalogIds("arroz")).contains(rice).doesNotContain(sugar, bean);
    }

    @Test
    void catalogSearchWithoutTermStillListsEverything() throws Exception {
        createProducts();

        assertThat(catalogIds(null)).contains(sugar, rice, bean);
    }

    @ParameterizedTest
    @CsvSource({"acucar,sugar", "ACUCAR,sugar", "Açúcar,sugar", "feij,bean", "FEIJÃO,bean"})
    void kitchenStockSearchIgnoresCaseAndAccents(String term, String expected) throws Exception {
        createProducts();
        for (int id : new int[] {sugar, rice, bean}) {
            send(put("/api/products/" + id + "/kitchen-parameters"),
                    "{\"kitchenId\":%d,\"minStock\":5}".formatted(kitchen.getId()))
                    .andExpect(status().isNoContent());
        }

        JsonNode rows = json(send(get("/api/kitchens/" + kitchen.getId() + "/stock").param("name", term), null)
                .andExpect(status().isOk()));
        List<Integer> ids = new ArrayList<>();
        rows.forEach(row -> ids.add(row.get("product").get("id").asInt()));

        assertThat(ids).containsExactly(expected.equals("sugar") ? sugar : bean);
    }

    private int createProduct(String name, int unitId, int supplierId) throws Exception {
        return createdId("/api/products", "{\"name\":\"%s\",\"unitId\":%d,\"supplierId\":%d}"
                .formatted(name, unitId, supplierId));
    }

    private List<Integer> catalogIds(String term) throws Exception {
        var request = get("/api/products").param("size", "100");
        if (term != null) {
            request.param("name", term);
        }
        JsonNode page = json(send(request, null).andExpect(status().isOk()));
        List<Integer> ids = new ArrayList<>();
        page.get("content").forEach(row -> ids.add(row.get("id").asInt()));
        return ids;
    }
}
