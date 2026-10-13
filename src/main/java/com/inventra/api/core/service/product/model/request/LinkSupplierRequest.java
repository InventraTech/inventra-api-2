package com.inventra.api.core.service.product.model.request;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record LinkSupplierRequest(
        @Schema(description = "Id do fornecedor", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Integer supplierId,

        @Schema(description = "Código do produto no fornecedor", example = "ARZ-001")
        @Size(max = 50)
        String supplierCode,

        @Schema(description = "Preço de referência", example = "25.90")
        @PositiveOrZero
        @Digits(integer = 10, fraction = 2)
        BigDecimal referencePrice,

        @Schema(description = "Prazo de entrega em dias", example = "3")
        @PositiveOrZero
        Integer leadTimeDays
) {
}
