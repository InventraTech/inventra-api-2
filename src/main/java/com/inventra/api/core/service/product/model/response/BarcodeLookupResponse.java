package com.inventra.api.core.service.product.model.response;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.inventra.api.core.domain.product.Product;
import com.inventra.api.core.service.stockbatch.StockBatchUseCase.ProductStock;
import com.inventra.api.infrastructure.client.openfoodfacts.model.OpenFoodFactsProduct;

// Resposta do scan. source = CATALOG: o produto já existe (product, e kitchenStock quando a consulta informa
// a cozinha). source = OPEN_FOOD_FACTS: só a base pública conhece o código (suggestion, para preencher o
// cadastro). Os campos que não se aplicam não aparecem no JSON.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BarcodeLookupResponse(
        Source source,
        String barcode,
        ProductSummary product,
        KitchenStock kitchenStock,
        OpenFoodFactsProduct suggestion
) {
    public enum Source {
        CATALOG,
        OPEN_FOOD_FACTS
    }

    public record ProductSummary(Integer id, String name, String brand, CategorySummary category, UnitSummary unit,
                                 String photoUrl, Boolean active) {
    }

    public record CategorySummary(Integer id, String name) {
    }

    public record UnitSummary(Integer id, String symbol) {
    }

    // mesmo critério do resumo de estoque da cozinha: lotes ativos e dentro da validade
    public record KitchenStock(BigDecimal totalQuantity, int activeBatches, LocalDate nextExpirationDate) {
    }

    public static BarcodeLookupResponse catalog(String barcode, Product product, ProductStock stock) {
        var category = product.getCategory() != null
                ? new CategorySummary(product.getCategory().getId(), product.getCategory().getName())
                : null;
        var kitchenStock = stock != null
                ? new KitchenStock(stock.totalQuantity(), stock.activeBatches(), stock.nextExpirationDate())
                : null;
        return new BarcodeLookupResponse(
                Source.CATALOG,
                barcode,
                new ProductSummary(product.getId(), product.getName(), product.getBrand(), category,
                        new UnitSummary(product.getUnit().getId(), product.getUnit().getSymbol()),
                        product.getPhotoUrl(), product.getActive()),
                kitchenStock,
                null);
    }

    public static BarcodeLookupResponse openFoodFacts(String barcode, OpenFoodFactsProduct suggestion) {
        return new BarcodeLookupResponse(Source.OPEN_FOOD_FACTS, barcode, null, null, suggestion);
    }
}
