package com.inventra.api.core.service.requisition.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelRequisitionRequest(
        @Schema(description = "Motivo", example = "Item não aprovado pelo orçamento", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 255)
        String reason
) {
}
