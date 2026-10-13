package com.inventra.api.core.service.stockbatch.model.response;

import java.math.BigDecimal;

import com.inventra.api.core.domain.product.Product;

public record LowStockAlertResponse(
        Integer kitchenId,
        Integer productId,
        ProductSummary product,
        BigDecimal currentQuantity,
        BigDecimal minStock
) {

    public record ProductSummary(Integer id, String name, UnitSummary unit) {
    }

    public record UnitSummary(Integer id, String symbol) {
    }

    public static LowStockAlertResponse of(Integer kitchenId, Product product, BigDecimal currentQuantity,
                                           BigDecimal minStock) {
        var unit = product.getUnit() != null
                ? new UnitSummary(product.getUnit().getId(), product.getUnit().getSymbol())
                : null;
        return new LowStockAlertResponse(
                kitchenId,
                product.getId(),
                new ProductSummary(product.getId(), product.getName(), unit),
                currentQuantity,
                minStock);
    }
}
