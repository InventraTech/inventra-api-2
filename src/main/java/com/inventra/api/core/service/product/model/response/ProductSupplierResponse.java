package com.inventra.api.core.service.product.model.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.inventra.api.core.domain.product.ProductSupplier;

public record ProductSupplierResponse(
        SupplierSummary supplier,
        String supplierCode,
        BigDecimal referencePrice,
        Integer leadTimeDays,
        LocalDateTime updatedAt
) {
    public record SupplierSummary(Integer id, String legalName) {
    }

    public static ProductSupplierResponse fromEntity(ProductSupplier link) {
        return new ProductSupplierResponse(
                new SupplierSummary(link.getSupplier().getId(), link.getSupplier().getLegalName()),
                link.getSupplierCode(),
                link.getReferencePrice(),
                link.getLeadTimeDays(),
                link.getUpdatedAt()
        );
    }
}
