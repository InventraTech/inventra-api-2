package com.inventra.api.core.service.stockbatch.model.response;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.inventra.api.core.domain.stock.StockBatch;
import com.inventra.api.core.domain.stock.enums.StockBatchStatus;

// Um lote afetado por uma baixa por produto: quanto saiu dele e o saldo que ficou.
public record BatchConsumptionResponse(
        BatchSummary batch,
        BigDecimal consumedQuantity,
        BigDecimal remainingQuantity,
        StockBatchStatus status
) {
    public record BatchSummary(Integer id, String batchNumber, LocalDate expirationDate) {
    }

    public static BatchConsumptionResponse of(StockBatch batch, BigDecimal consumed) {
        BigDecimal remaining = batch.getCurrentQuantity().subtract(consumed);
        return new BatchConsumptionResponse(
                new BatchSummary(batch.getId(), batch.getBatchNumber(), batch.getExpirationDate()),
                consumed,
                remaining,
                // mesmo efeito do trigger trg_update_batch_status: saldo zerado encerra o lote
                remaining.signum() == 0 ? StockBatchStatus.WRITTEN_OFF : batch.getStatus());
    }
}
