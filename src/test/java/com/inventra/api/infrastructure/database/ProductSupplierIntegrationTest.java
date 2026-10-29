package com.inventra.api.infrastructure.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.EntityManager;

// Fornecedores da cozinha (GET /api/suppliers?kitchenId) e ordem dos vínculos do produto
// (GET /api/products/{id}/suppliers) contra o Postgres: a consulta com EXISTS e o updated_at da V020, com o
// DEFAULT no INSERT e o trg_product_supplier_updated_at no UPDATE. O produto nasce já vinculado ao fornecedor
// informado no cadastro.
class ProductSupplierIntegrationTest extends PostgresIntegrationTest {

    @Autowired private EntityManager entityManager;

    @Test
    void kitchenSuppliersIncludeSuppliersLinkedToKitchenProductsEvenWithoutBatches() throws Exception {
        // compra planejada: produto cadastrado com fornecedor e estoque mínimo na cozinha, sem nenhum lote
        int linked = createSupplier("Atacadão Vinculado");
        int plannedProduct = createProduct(linked);
        setMinimumStock(plannedProduct, kitchen.getId());

        // o caso de sempre: fornecedor que já entregou lote na cozinha
        int withBatch = createSupplier("Distribuidora do Lote");
        createBatch(createProduct(createSupplier("Fornecedor do Cadastro")), withBatch);

        // vinculado só a produto de outra cozinha
        int otherKitchen = jdbc.queryForObject(
                "INSERT INTO tb_kitchen (name, code) VALUES ('Outra cozinha', ?) RETURNING id_kitchen", Integer.class,
                "OT-" + UUID.randomUUID().toString().substring(0, 8));
        int otherKitchenSupplier = createSupplier("Fornecedor de Outra Cozinha");
        int otherProduct = createProduct(otherKitchenSupplier);
        jdbc.update("INSERT INTO tb_product_kitchen_parameter (id_product, id_kitchen, min_stock) VALUES (?, ?, 1)",
                otherProduct, otherKitchen);

        // fornecedor desativado, mesmo vinculado a produto da cozinha
        int inactive = createSupplier("Fornecedor Desativado");
        link(plannedProduct, inactive);
        send(patch("/api/suppliers/" + inactive + "/deactivate"), null).andExpect(status().isNoContent());

        // produto desativado não leva o fornecedor para a cozinha
        int ofInactiveProduct = createSupplier("Fornecedor de Produto Desativado");
        int inactiveProduct = createProduct(ofInactiveProduct);
        setMinimumStock(inactiveProduct, kitchen.getId());
        send(patch("/api/products/" + inactiveProduct + "/deactivate"), null).andExpect(status().isNoContent());

        List<Integer> ids = ids(json(send(get("/api/suppliers?kitchenId=" + kitchen.getId()), null)
                .andExpect(status().isOk())));

        assertThat(ids).contains(linked, withBatch)
                .doesNotContain(otherKitchenSupplier, inactive, ofInactiveProduct);
    }

    @Test
    void productSuppliersComeNewestFirstAndRelinkingMovesALinkToTheTop() throws Exception {
        int oldest = createSupplier("Fornecedor Antigo");
        int middle = createSupplier("Fornecedor do Meio");
        int newest = createSupplier("Fornecedor Novo");
        int product = createProduct(oldest);
        // o cadastro já vinculou o mais antigo; os vínculos são refeitos com datas passadas direto no INSERT
        // (o trigger da V020 é só de UPDATE). flush antes e clear depois: o JDBC passa por fora do Hibernate, que
        // senão gravaria depois o vínculo que ainda guarda em memória
        entityManager.flush();
        jdbc.update("DELETE FROM tb_product_supplier WHERE id_product = ?", product);
        jdbc.update("INSERT INTO tb_product_supplier (id_product, id_supplier, reference_price, updated_at) "
                + "VALUES (?, ?, 10.00, CURRENT_TIMESTAMP - INTERVAL '2 days'), (?, ?, NULL, CURRENT_TIMESTAMP - INTERVAL '1 day')",
                product, oldest, product, middle);
        entityManager.clear();

        assertThat(linkedSupplierIds(product)).containsExactly(middle, oldest);

        // regravar o mais antigo só com o fornecedor (como o app faz na edição) o leva para o topo e mantém o preço
        link(product, oldest);
        assertThat(linkedSupplierIds(product)).containsExactly(oldest, middle);
        assertThat(jdbc.queryForObject("SELECT reference_price FROM tb_product_supplier WHERE id_product = ? "
                + "AND id_supplier = ?", BigDecimal.class, product, oldest)).isEqualByComparingTo("10.00");

        // vínculo novo (INSERT, data do DEFAULT) também vem antes dos antigos. O teste roda numa transação só, então
        // ele e o regravado têm a mesma data: vale o desempate pelo fornecedor, o maior id primeiro
        link(product, newest);
        assertThat(linkedSupplierIds(product)).containsExactly(newest, oldest, middle);
        json(send(get("/api/products/" + product + "/suppliers"), null).andExpect(status().isOk()))
                .forEach(item -> assertThat(item.get("updatedAt").isNull()).isFalse());
    }

    private List<Integer> linkedSupplierIds(int productId) throws Exception {
        JsonNode links = json(send(get("/api/products/" + productId + "/suppliers"), null).andExpect(status().isOk()));
        List<Integer> ids = new ArrayList<>();
        links.forEach(item -> ids.add(item.get("supplier").get("id").asInt()));
        return ids;
    }

    private static List<Integer> ids(JsonNode array) {
        List<Integer> ids = new ArrayList<>();
        array.forEach(item -> ids.add(item.get("id").asInt()));
        return ids;
    }

    private int createProduct(int supplierId) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        int unitId = createdId("/api/units", "{\"symbol\":\"u%s\",\"description\":\"Unidade %s\"}".formatted(suffix, suffix));
        return createdId("/api/products", "{\"name\":\"Ketchup %s\",\"unitId\":%d,\"supplierId\":%d}"
                .formatted(suffix, unitId, supplierId));
    }

    private int createSupplier(String legalName) throws Exception {
        String cnpj = String.valueOf(ThreadLocalRandom.current().nextLong(10_000_000_000_000L, 99_999_999_999_999L));
        return createdId("/api/suppliers", "{\"legalName\":\"%s\",\"cnpj\":\"%s\"}".formatted(legalName, cnpj));
    }

    private void link(int productId, int supplierId) throws Exception {
        send(post("/api/products/" + productId + "/suppliers"), "{\"supplierId\":%d}".formatted(supplierId))
                .andExpect(status().isNoContent());
    }

    private void setMinimumStock(int productId, int kitchenId) throws Exception {
        send(put("/api/products/" + productId + "/kitchen-parameters"),
                "{\"kitchenId\":%d,\"minStock\":5}".formatted(kitchenId))
                .andExpect(status().isNoContent());
    }

    private void createBatch(int productId, int supplierId) throws Exception {
        createdId("/api/stock-batches", ("{\"productId\":%d,\"kitchenId\":%d,\"supplierId\":%d,\"batchNumber\":\"L-%s\","
                + "\"initialQuantity\":10,\"entryDate\":\"%s\"}")
                .formatted(productId, kitchen.getId(), supplierId, UUID.randomUUID().toString().substring(0, 8),
                        LocalDate.now()));
    }
}
