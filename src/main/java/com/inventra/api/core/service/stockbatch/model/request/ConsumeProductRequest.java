package com.inventra.api.core.service.stockbatch.model.request;

import java.math.BigDecimal;

import com.inventra.api.core.domain.stock.enums.StockMovementReason;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ConsumeProductRequest(
        @Schema(description = "Id da cozinha", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Integer kitchenId,

        @Schema(description = "Id do produto", example = "5013", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Integer productId,

        @Schema(description = "Quantidade", example = "6.000", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        @Positive
        @Digits(integer = 9, fraction = 3)
        BigDecimal quantity,

        @Schema(description = "Motivo da baixa: USAGE (padrão), LOSS ou EXPIRED", example = "USAGE")
        StockMovementReason reason
) {
}
