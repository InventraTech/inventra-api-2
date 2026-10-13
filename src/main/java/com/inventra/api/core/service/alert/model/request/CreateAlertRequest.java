package com.inventra.api.core.service.alert.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.inventra.api.core.domain.alert.enums.AlertSeverity;

public record CreateAlertRequest(
        @Schema(description = "Tipo do alerta", example = "LOW_STOCK", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 30)
        String type,

        @Schema(description = "Severidade do alerta", example = "MEDIUM", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        AlertSeverity severity,

        @Schema(description = "Id do lote", example = "1")
        Integer batchId,

        @Schema(description = "Id do produto", example = "1")
        Integer productId,

        @Schema(description = "Id da cozinha", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Integer kitchenId,

        @Schema(description = "Mensagem do alerta", example = "Estoque de arroz abaixo do mínimo", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 255)
        String message
) {
}
