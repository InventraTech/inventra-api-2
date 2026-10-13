package com.inventra.api.core.service.kitchenaccess.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public record RejectKitchenAccessRequest(
        @Schema(description = "Motivo da rejeição (opcional)", example = "Código usado por engano")
        @Size(max = 255)
        String reason
) {
}
