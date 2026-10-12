package com.inventra.api.core.service.stockbatch.model.response;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.inventra.api.core.domain.product.Product;

// Linha da tela de estoque: um produto ligado à cozinha com o saldo utilizável (lotes ativos e não vencidos).
public record KitchenStockItemResponse(
        ProductSummary product,
        CategorySummary category,
        UnitSummary unit,
        BigDecimal totalQuantity,
        int activeBatches,
        LocalDate nextExpirationDate,
        BigDecimal minStock,
        boolean lowStock
) {

    public record ProductSummary(Integer id, String name, String brand, String photoUrl) {
    }

    public record CategorySummary(Integer id, String name) {
    }

    public record UnitSummary(Integer id, String symbol) {
    }

    public static KitchenStockItemResponse of(Product product, BigDecimal totalQuantity, int activeBatches,
                                              LocalDate nextExpirationDate, BigDecimal minStock) {
        var category = product.getCategory() != null
                ? new CategorySummary(product.getCategory().getId(), product.getCategory().getName())
                : null;
        var unit = new UnitSummary(product.getUnit().getId(), product.getUnit().getSymbol());
        // mesma regra do alerta de estoque baixo: saldo estritamente abaixo do mínimo
        boolean lowStock = minStock != null && minStock.compareTo(totalQuantity) > 0;
        return new KitchenStockItemResponse(
                new ProductSummary(product.getId(), product.getName(), product.getBrand(), product.getPhotoUrl()),
                category,
                unit,
                totalQuantity,
                activeBatches,
                nextExpirationDate,
                minStock,
                lowStock);
    }
}
