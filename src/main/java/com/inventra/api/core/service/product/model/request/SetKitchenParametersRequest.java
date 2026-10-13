package com.inventra.api.core.service.product.model.request;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record SetKitchenParametersRequest(
        @Schema(description = "Id da cozinha", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Integer kitchenId,

        @Schema(description = "Estoque mínimo na cozinha", example = "10.000")
        @PositiveOrZero
        @Digits(integer = 9, fraction = 3)
        BigDecimal minStock,

        @Schema(description = "Estoque máximo na cozinha", example = "50.000")
        @PositiveOrZero
        @Digits(integer = 9, fraction = 3)
        BigDecimal maxStock,

        @Schema(description = "Consumo médio diário", example = "2.500")
        @PositiveOrZero
        @Digits(integer = 9, fraction = 3)
        BigDecimal averageDailyConsumption
) {
}
