package com.inventra.api.core.service.stockbatch.model.request;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record AdjustStockRequest(
        @Schema(description = "Nova quantidade do lote", example = "8.000", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        @PositiveOrZero
        @Digits(integer = 9, fraction = 3)
        BigDecimal newQuantity
) {
}
