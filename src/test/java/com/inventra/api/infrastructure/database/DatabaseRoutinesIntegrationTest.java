package com.inventra.api.infrastructure.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.inventra.api.infrastructure.repository.StockBatchRepository;

import jakarta.persistence.EntityManager;

// Ligação Java ↔ PL/pgSQL de ponta a ponta: cada teste chama a API (ou o repository, no caso do job) e
// confere no banco o efeito das procedures (CALL sp_*) e dos triggers (fn_*).
class DatabaseRoutinesIntegrationTest extends PostgresIntegrationTest {

    @Autowired private StockBatchRepository stockBatchRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void migrationsCreateEveryProcedureTheRepositoriesCall() {
        List<String> procedures = jdbc.queryForList(
                "SELECT proname FROM pg_proc WHERE prokind = 'p' AND proname LIKE 'sp\\_%'", String.class);

        assertThat(procedures).contains("sp_register_stock_entry", "sp_write_off_stock", "sp_expire_batches",
                "sp_alert_expiring_batches", "sp_approve_requisition", "sp_reject_requisition",
                "sp_cancel_requisition", "sp_close_inventory");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE NOT success", Integer.class))
                .isZero();
    }

    @Test
    void restockAndConsumeRunTheProceduresAndRecordMovements() throws Exception {
        int batchId = createBatch(createProduct(), "10", LocalDate.now(), null);

        send(patch("/api/stock-batches/" + batchId + "/restock"), "{\"quantity\":5}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentQuantity").value(15));
        // a baixa zera o lote: o trigger trg_update_batch_status passa o status para WRITTEN_OFF
        send(patch("/api/stock-batches/" + batchId + "/consume"), "{\"quantity\":15,\"reason\":\"LOSS\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentQuantity").value(0))
                .andExpect(jsonPath("$.status").value("WRITTEN_OFF"));

        // a criação do lote é gravada pelo Java; a entrada e a baixa, pelas procedures
        List<String> movements = jdbc.queryForList(
                "SELECT type || ':' || COALESCE(reason, '-') || ':' || balance_after FROM tb_stock_movement "
                        + "WHERE id_batch = ? ORDER BY id_movement", String.class, batchId);
        assertThat(movements).containsExactly("ENTRY:-:10.000", "ENTRY:-:15.000", "WRITE_OFF:LOSS:0.000");
    }

    @Test
    void expireBatchesProcedureMarksExpiredBatchesAndRaisedErrorBecomesConflict() throws Exception {
        LocalDate today = LocalDate.now();
        int batchId = createBatch(createProduct(), "8", today.minusDays(10), today.minusDays(1));

        // o mesmo CALL que o BatchExpirationJob faz todo dia
        stockBatchRepository.callExpireBatches();

        assertThat(jdbc.queryForObject("SELECT status FROM tb_stock_batch WHERE id_batch = ?", String.class, batchId))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_alert WHERE id_batch = ? AND type = 'EXPIRATION'", Integer.class, batchId))
                .isEqualTo(1);

        // o Java não barra a entrada em lote vencido: quem barra é o RAISE EXCEPTION de sp_register_stock_entry,
        // que chega com SQLState P0001 e vira 409 com a mensagem da procedure
        send(patch("/api/stock-batches/" + batchId + "/restock"), "{\"quantity\":1}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Não é possível dar entrada em lote vencido ou cancelado."));
    }

    @Test
    void approvingAndCancellingRequisitionMoveStockThroughProcedures() throws Exception {
        int productId = createProduct();
        int batchId = createBatch(productId, "10", LocalDate.now(), null);
        int requisitionId = createdId("/api/requisitions",
                "{\"type\":\"CONSUMPTION\",\"origin\":\"Cozinha\",\"kitchenId\":%d}".formatted(kitchen.getId()));
        send(post("/api/requisitions/" + requisitionId + "/items"),
                "{\"productId\":%d,\"quantity\":4}".formatted(productId)).andExpect(status().isOk());
        send(patch("/api/requisitions/" + requisitionId + "/submit"), null).andExpect(status().isOk());

        // sp_approve_requisition muda o status; o trigger trg_requisition_approval preenche approved_at
        send(patch("/api/requisitions/" + requisitionId + "/approve"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedAt").isNotEmpty());
        assertThat(currentQuantity(batchId)).isEqualByComparingTo("6");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_stock_movement WHERE id_batch = ? "
                + "AND type = 'WRITE_OFF' AND reason = 'REQUISITION' AND id_requisition = ?",
                Integer.class, batchId, requisitionId)).isEqualTo(1);

        // sp_cancel_requisition devolve aos lotes o que a aprovação baixou
        send(patch("/api/requisitions/" + requisitionId + "/cancel"), "{\"reason\":\"Pedido duplicado\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(currentQuantity(batchId)).isEqualByComparingTo("10");
    }

    @Test
    void closingInventoryAppliesDivergenceAndRunsTheProcedure() throws Exception {
        int batchId = createBatch(createProduct(), "10", LocalDate.now(), null);
        int inventoryId = createdId("/api/inventories", "{\"kitchenId\":%d}".formatted(kitchen.getId()));
        send(post("/api/inventories/" + inventoryId + "/counts"),
                "{\"batchId\":%d,\"physicalQuantity\":7}".formatted(batchId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.divergence").value(-3));

        // sp_close_inventory fecha o inventário e preenche closed_at
        send(patch("/api/inventories/" + inventoryId + "/close"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());
        assertThat(currentQuantity(batchId)).isEqualByComparingTo("7");
        assertThat(jdbc.queryForObject("SELECT divergence FROM tb_inventory_count WHERE id_inventory = ?",
                BigDecimal.class, inventoryId)).isEqualByComparingTo("-3");
    }

    @Test
    void stockAlertTriggerFiresWhenBalanceDropsBelowMinimum() throws Exception {
        int productId = createProduct();
        send(put("/api/products/" + productId + "/kitchen-parameters"),
                "{\"kitchenId\":%d,\"minStock\":5,\"maxStock\":50}".formatted(kitchen.getId()))
                .andExpect(status().isNoContent());
        int batchId = createBatch(productId, "10", LocalDate.now(), null);
        assertThat(stockAlerts(productId)).isZero();

        send(patch("/api/stock-batches/" + batchId + "/consume"), "{\"quantity\":6}").andExpect(status().isOk());

        // fn_stock_alert (trigger AFTER UPDATE em tb_stock_batch) abre um alerta só, mesmo com novas baixas
        assertThat(stockAlerts(productId)).isEqualTo(1);
        send(patch("/api/stock-batches/" + batchId + "/consume"), "{\"quantity\":1}").andExpect(status().isOk());
        assertThat(stockAlerts(productId)).isEqualTo(1);
    }

    @Test
    void auditTriggerLogsProductChanges() throws Exception {
        int productId = createProduct();
        send(put("/api/products/" + productId), "{\"brand\":\"Marca Nova\"}").andExpect(status().isOk());
        // o teste roda numa transação só: o UPDATE do Hibernate precisa chegar ao banco para o trigger disparar
        entityManager.flush();

        List<String> operations = jdbc.queryForList(
                "SELECT operation FROM tb_log_product WHERE id_product = ? ORDER BY id_log", String.class, productId);
        assertThat(operations).containsExactly("INSERT", "UPDATE");
        assertThat(jdbc.queryForObject("SELECT new_data ->> 'brand' FROM tb_log_product "
                + "WHERE id_product = ? AND operation = 'UPDATE'", String.class, productId)).isEqualTo("Marca Nova");
    }

    // ===== helpers =====

    private int createProduct() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        int unitId = createdId("/api/units", "{\"symbol\":\"u%s\",\"description\":\"Unidade %s\"}".formatted(suffix, suffix));
        // produto não nasce sem fornecedor
        int supplierId = createdId("/api/suppliers", "{\"legalName\":\"Fornecedor %s\",\"cnpj\":\"%s\"}".formatted(suffix,
                ThreadLocalRandom.current().nextLong(10_000_000_000_000L, 99_999_999_999_999L)));
        return createdId("/api/products", "{\"name\":\"Arroz %s\",\"unitId\":%d,\"supplierId\":%d}"
                .formatted(suffix, unitId, supplierId));
    }

    private int createBatch(int productId, String quantity, LocalDate entryDate, LocalDate expirationDate)
            throws Exception {
        String expiration = expirationDate == null ? "null" : "\"" + expirationDate + "\"";
        return createdId("/api/stock-batches", ("{\"productId\":%d,\"kitchenId\":%d,\"batchNumber\":\"L-%s\","
                + "\"initialQuantity\":%s,\"entryDate\":\"%s\",\"expirationDate\":%s}")
                .formatted(productId, kitchen.getId(), UUID.randomUUID().toString().substring(0, 8), quantity,
                        entryDate, expiration));
    }

    private BigDecimal currentQuantity(int batchId) {
        return jdbc.queryForObject("SELECT current_quantity FROM tb_stock_batch WHERE id_batch = ?",
                BigDecimal.class, batchId);
    }

    private int stockAlerts(int productId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tb_alert WHERE id_product = ? AND id_kitchen = ? "
                + "AND type = 'STOCK'", Integer.class, productId, kitchen.getId());
    }
}
