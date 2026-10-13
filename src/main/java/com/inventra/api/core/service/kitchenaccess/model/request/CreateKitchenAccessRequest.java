package com.inventra.api.core.service.kitchenaccess.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

// Exatamente um dos dois: kitchenId (cozinha escolhida na descoberta) ou code (convite).
public record CreateKitchenAccessRequest(
        @Schema(description = "Id da cozinha escolhida na descoberta", example = "7")
        Integer kitchenId,

        @Schema(description = "Código de convite da cozinha", example = "AB12CD34")
        @Size(max = 20)
        String code
) {
}
