package com.inventra.api.core.service.stockbatch.model.response;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.inventra.api.core.domain.stock.StockBatch;
import com.inventra.api.core.domain.stock.enums.StockBatchStatus;

public record StockBatchResponse(
        Integer id,
        ProductSummary product,
        KitchenSummary kitchen,
        SupplierSummary supplier,
        String batchNumber,
        String invoiceNumber,
        BigDecimal initialQuantity,
        BigDecimal currentQuantity,
        LocalDate entryDate,
        LocalDate expirationDate,
        BigDecimal unitPrice,
        StockBatchStatus status
) {
    public record ProductSummary(Integer id, String name, String photoUrl) {
    }

    public record KitchenSummary(Integer id, String name) {
    }

    public record SupplierSummary(Integer id, String legalName) {
    }

    public static StockBatchResponse fromEntity(StockBatch batch) {
        var supplier = batch.getSupplier() != null
                ? new SupplierSummary(batch.getSupplier().getId(), batch.getSupplier().getLegalName())
                : null;

        return new StockBatchResponse(
                batch.getId(),
                new ProductSummary(batch.getProduct().getId(), batch.getProduct().getName(),
                        batch.getProduct().getPhotoUrl()),
                new KitchenSummary(batch.getKitchen().getId(), batch.getKitchen().getName()),
                supplier,
                batch.getBatchNumber(),
                batch.getInvoiceNumber(),
                batch.getInitialQuantity(),
                batch.getCurrentQuantity(),
                batch.getEntryDate(),
                batch.getExpirationDate(),
                batch.getUnitPrice(),
                batch.getStatus()
        );
    }
}
