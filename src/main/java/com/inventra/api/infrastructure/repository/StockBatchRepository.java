package com.inventra.api.infrastructure.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.inventra.api.core.domain.stock.StockBatch;
import com.inventra.api.core.domain.stock.enums.StockBatchStatus;
import com.inventra.api.core.domain.supplier.Supplier;
import com.inventra.api.core.service.ranking.RankingCandidate;

import jakarta.persistence.LockModeType;

public interface StockBatchRepository extends JpaRepository<StockBatch, Integer> {

    @EntityGraph(attributePaths = {"product", "kitchen", "supplier"})
    List<StockBatch> findByKitchenId(Integer kitchenId);

    @EntityGraph(attributePaths = {"product", "kitchen", "supplier"})
    List<StockBatch> findByKitchenIdAndProductId(Integer kitchenId, Integer productId);

    @Query("""
            select new com.inventra.api.core.service.ranking.RankingCandidate(b.id, b.expirationDate)
            from StockBatch b
            where b.kitchen.id = :kitchenId and b.status = com.inventra.api.core.domain.stock.enums.StockBatchStatus.ACTIVE
              and b.currentQuantity > 0 and b.expirationDate is not null
            """)
    List<RankingCandidate> findRankingCandidates(@Param("kitchenId") Integer kitchenId);

    @EntityGraph(attributePaths = {"product", "kitchen", "supplier"})
    List<StockBatch> findByKitchenIdAndStatusAndExpirationDateBetween(
            Integer kitchenId, StockBatchStatus status, LocalDate start, LocalDate end);

    // Trava a linha (SELECT ... FOR UPDATE) até o fim da transação: ajustes concorrentes no mesmo lote
    // esperam um ao outro em vez de um sobrescrever o saldo gravado pelo outro.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT sb FROM StockBatch sb WHERE sb.id = :id")
    Optional<StockBatch> findByIdForUpdate(@Param("id") Integer id);

    // Lotes utilizáveis em FIFO por validade: ACTIVE e ainda dentro da validade (lote vencido nunca é
    // consumido, mesmo antes do job diário marcá-lo como EXPIRED). Trava as linhas até o fim da transação:
    // duas baixas simultâneas do mesmo produto esperam uma à outra em vez de calcularem sobre o mesmo saldo.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT sb FROM StockBatch sb
            WHERE sb.kitchen.id = :kitchenId AND sb.product.id = :productId AND sb.status = 'ACTIVE'
              AND (sb.expirationDate IS NULL OR sb.expirationDate >= :today)
            ORDER BY sb.expirationDate ASC NULLS LAST, sb.entryDate ASC
            """)
    List<StockBatch> findUsableForConsumption(@Param("kitchenId") Integer kitchenId,
                                              @Param("productId") Integer productId,
                                              @Param("today") LocalDate today);

    @Query("""
            SELECT COALESCE(SUM(sb.currentQuantity), 0) FROM StockBatch sb
            WHERE sb.product.id = :productId AND sb.kitchen.id = :kitchenId AND sb.status = 'ACTIVE'
              AND (sb.expirationDate IS NULL OR sb.expirationDate >= :today)
            """)
    BigDecimal sumUsableQuantity(@Param("productId") Integer productId, @Param("kitchenId") Integer kitchenId,
                                 @Param("today") LocalDate today);

    // Saldo utilizável de vários produtos da cozinha numa consulta só: linhas [productId, saldo].
    // Produto sem lote utilizável não aparece (saldo zero).
    @Query("""
            SELECT sb.product.id, SUM(sb.currentQuantity) FROM StockBatch sb
            WHERE sb.kitchen.id = :kitchenId AND sb.product.id IN :productIds AND sb.status = 'ACTIVE'
              AND (sb.expirationDate IS NULL OR sb.expirationDate >= :today)
            GROUP BY sb.product.id
            """)
    List<Object[]> sumUsableQuantityByProduct(@Param("kitchenId") Integer kitchenId,
                                              @Param("productIds") java.util.Collection<Integer> productIds,
                                              @Param("today") LocalDate today);

    // Resumo dos lotes utilizáveis de cada produto da cozinha, numa consulta só:
    // linhas [productId, saldo, quantidade de lotes, menor validade (ou null)].
    @Query("""
            SELECT sb.product.id, SUM(sb.currentQuantity), COUNT(sb), MIN(sb.expirationDate) FROM StockBatch sb
            WHERE sb.kitchen.id = :kitchenId AND sb.status = 'ACTIVE'
              AND (sb.expirationDate IS NULL OR sb.expirationDate >= :today)
            GROUP BY sb.product.id
            """)
    List<Object[]> summarizeUsableByProduct(@Param("kitchenId") Integer kitchenId, @Param("today") LocalDate today);

    // Resumo dos lotes utilizáveis de um produto na cozinha: uma linha [saldo (ou null), quantidade de lotes,
    // menor validade (ou null)].
    @Query("""
            SELECT SUM(sb.currentQuantity), COUNT(sb), MIN(sb.expirationDate) FROM StockBatch sb
            WHERE sb.kitchen.id = :kitchenId AND sb.product.id = :productId AND sb.status = 'ACTIVE'
              AND (sb.expirationDate IS NULL OR sb.expirationDate >= :today)
            """)
    List<Object[]> summarizeUsableForProduct(@Param("kitchenId") Integer kitchenId,
                                             @Param("productId") Integer productId,
                                             @Param("today") LocalDate today);

    // Fornecedores ativos que já entregaram algum lote na cozinha.
    @Query("""
            SELECT DISTINCT s FROM StockBatch sb
            JOIN sb.supplier s
            WHERE sb.kitchen.id = :kitchenId AND s.active = true
            ORDER BY s.legalName
            """)
    List<Supplier> findActiveSuppliersByKitchen(@Param("kitchenId") Integer kitchenId);

    // Saldo que conta como estoque: lotes ativos e não vencidos.
    default BigDecimal sumActiveQuantity(Integer productId, Integer kitchenId) {
        return sumUsableQuantity(productId, kitchenId, LocalDate.now());
    }

    // Procedures criadas em V002__business_rules.sql e recriadas em V007__stock_movements.sql, quando
    // passaram a gravar a movimentação (tb_stock_movement) com o usuário e o motivo. O trigger
    // trg_update_batch_status marca o lote como WRITTEN_OFF quando o saldo zera. Os parâmetros nunca são
    // nulos: o Postgres não resolve a procedure quando o driver manda um nulo sem tipo.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "CALL sp_register_stock_entry(:batchId, :quantity, :userId)", nativeQuery = true)
    void callRegisterStockEntry(@Param("batchId") Integer batchId, @Param("quantity") BigDecimal quantity,
                                @Param("userId") UUID userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "CALL sp_write_off_stock(:batchId, :quantity, :userId, :reason)", nativeQuery = true)
    void callWriteOffStock(@Param("batchId") Integer batchId, @Param("quantity") BigDecimal quantity,
                           @Param("userId") UUID userId, @Param("reason") String reason);

    // Baixa de uma requisição aprovada: a movimentação guarda a requisição, e é por ela que
    // sp_cancel_requisition sabe o que devolver aos lotes num cancelamento.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "CALL sp_write_off_stock(:batchId, :quantity, :userId, :reason, :requisitionId)", nativeQuery = true)
    void callWriteOffStockForRequisition(@Param("batchId") Integer batchId, @Param("quantity") BigDecimal quantity,
                                         @Param("userId") UUID userId, @Param("reason") String reason,
                                         @Param("requisitionId") Integer requisitionId);

    // Procedure criada em V002__business_rules.sql: marca como EXPIRED os lotes vencidos e gera os alertas.
    // Transacional aqui (e não só no job) para funcionar também quando chamado de dentro do próprio job.
    @Transactional
    @Modifying
    @Query(value = "CALL sp_expire_batches()", nativeQuery = true)
    void callExpireBatches();

    // Procedure criada em V008__alert_recipients_and_reads.sql: gera o alerta EXPIRING para os lotes ativos
    // que vencem em até "days" dias, sem duplicar enquanto o alerta do lote estiver aberto.
    @Transactional
    @Modifying
    @Query(value = "CALL sp_alert_expiring_batches(:days)", nativeQuery = true)
    void callAlertExpiringBatches(@Param("days") int days);
}
