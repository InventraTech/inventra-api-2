package com.inventra.api.core.service.unit.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUnitRequest(
        @Schema(description = "Símbolo", example = "kg", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 10)
        String symbol,

        @Schema(description = "Descrição da unidade", example = "Quilograma", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 60)
        String description
) {
}
