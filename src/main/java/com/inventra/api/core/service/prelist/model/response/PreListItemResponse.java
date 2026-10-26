package com.inventra.api.core.service.prelist.model.response;

import java.math.BigDecimal;

import com.inventra.api.core.domain.prelist.PreListItem;

public record PreListItemResponse(
        Integer id,
        ProductSummary product,
        SupplierSummary supplier,
        BigDecimal quantity
) {
    public record ProductSummary(Integer id, String name, String photoUrl) {
    }

    public record SupplierSummary(Integer id, String legalName) {
    }

    public static PreListItemResponse fromEntity(PreListItem item) {
        return new PreListItemResponse(
                item.getId(),
                new ProductSummary(item.getProduct().getId(), item.getProduct().getName(),
                        item.getProduct().getPhotoUrl()),
                new SupplierSummary(item.getSupplier().getId(), item.getSupplier().getLegalName()),
                item.getQuantity()
        );
    }
}
