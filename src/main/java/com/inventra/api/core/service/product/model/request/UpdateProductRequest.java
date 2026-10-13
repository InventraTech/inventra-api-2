package com.inventra.api.core.service.product.model.request;

public record UpdateProductRequest(
        String name,
        String brand,
        Integer categoryId,
        Integer unitId,
        String barcode
) {
}
