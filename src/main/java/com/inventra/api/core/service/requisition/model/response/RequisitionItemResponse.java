package com.inventra.api.core.service.requisition.model.response;

import java.math.BigDecimal;

import com.inventra.api.core.domain.requisition.RequisitionItem;

public record RequisitionItemResponse(
        Integer id,
        ProductSummary product,
        BigDecimal quantity,
        BigDecimal estimatedPrice,
        SupplierSummary suggestedSupplier,
        String note
) {
    public record ProductSummary(Integer id, String name, String photoUrl) {
    }

    public record SupplierSummary(Integer id, String legalName) {
    }

    public static RequisitionItemResponse fromEntity(RequisitionItem item) {
        var suggestedSupplier = item.getSuggestedSupplier() != null
                ? new SupplierSummary(item.getSuggestedSupplier().getId(), item.getSuggestedSupplier().getLegalName())
                : null;

        return new RequisitionItemResponse(
                item.getId(),
                new ProductSummary(item.getProduct().getId(), item.getProduct().getName(),
                        item.getProduct().getPhotoUrl()),
                item.getQuantity(),
                item.getEstimatedPrice(),
                suggestedSupplier,
                item.getNote()
        );
    }
}
