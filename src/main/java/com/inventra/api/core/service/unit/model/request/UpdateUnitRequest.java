package com.inventra.api.core.service.unit.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateUnitRequest(
        @Schema(description = "Símbolo", example = "kg")
        @Size(max = 10)
        @Pattern(regexp = ".*\\S.*", message = "não pode ser vazio")
        String symbol,

        @Schema(description = "Descrição", example = "Descrição opcional")
        @Size(max = 60)
        String description
) {
}
