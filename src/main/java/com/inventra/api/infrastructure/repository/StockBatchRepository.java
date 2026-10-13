package com.inventra.api.infrastructure.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.inventra.api.core.domain.stock.StockBatch;
import com.inventra.api.core.domain.stock.enums.StockBatchStatus;

public interface StockBatchRepository extends JpaRepository<StockBatch, Integer> {

    List<StockBatch> findByKitchenId(Integer kitchenId);

    List<StockBatch> findByProductId(Integer productId);

    List<StockBatch> findByKitchenIdAndProductIdAndStatusOrderByExpirationDateAscEntryDateAsc(
            Integer kitchenId, Integer productId, StockBatchStatus status);

    List<StockBatch> findByKitchenIdAndStatusAndExpirationDateBetween(
            Integer kitchenId, StockBatchStatus status, LocalDate start, LocalDate end);

    @Query("""
            SELECT COALESCE(SUM(sb.currentQuantity), 0) FROM StockBatch sb
            WHERE sb.product.id = :productId AND sb.kitchen.id = :kitchenId AND sb.status = 'ACTIVE'
            """)
    BigDecimal sumActiveQuantity(@Param("productId") Integer productId, @Param("kitchenId") Integer kitchenId);

    // Procedures criadas em V3__business_rules.sql. O trigger trg_update_batch_status marca o lote
    // como WRITTEN_OFF quando o saldo zera.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "CALL sp_register_stock_entry(:batchId, :quantity)", nativeQuery = true)
    void callRegisterStockEntry(@Param("batchId") Integer batchId, @Param("quantity") BigDecimal quantity);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "CALL sp_write_off_stock(:batchId, :quantity)", nativeQuery = true)
    void callWriteOffStock(@Param("batchId") Integer batchId, @Param("quantity") BigDecimal quantity);

}
