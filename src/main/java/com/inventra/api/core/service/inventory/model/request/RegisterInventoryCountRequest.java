package com.inventra.api.core.service.inventory.model.request;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record RegisterInventoryCountRequest(
        @Schema(description = "Id do lote", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Integer batchId,

        @Schema(description = "Quantidade física contada", example = "12.500", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        @PositiveOrZero
        @Digits(integer = 9, fraction = 3)
        BigDecimal physicalQuantity,

        @Schema(description = "Observação", example = "Contagem mensal")
        @Size(max = 255)
        String note
) {
}
