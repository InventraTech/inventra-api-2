package com.inventra.api.core.service.requisition.model.request;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AddRequisitionItemRequest(
        @Schema(description = "Id do produto", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Integer productId,

        @Schema(description = "Quantidade", example = "5.000", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        @Positive
        @Digits(integer = 9, fraction = 3)
        BigDecimal quantity,

        @Schema(description = "Id do fornecedor sugerido", example = "1")
        Integer suggestedSupplierId,

        @Schema(description = "Preço estimado", example = "25.90")
        @PositiveOrZero
        @Digits(integer = 10, fraction = 2)
        BigDecimal estimatedPrice,

        @Schema(description = "Observação", example = "Contagem mensal")
        @Size(max = 255)
        String note
) {
}
