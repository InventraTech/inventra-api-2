package com.inventra.api.core.service.requisition.model.request;

import com.inventra.api.core.domain.requisition.enums.RequisitionType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// O solicitante é o usuário logado (não vem do body).
public record CreateRequisitionRequest(
        @Schema(description = "Tipo da requisição (CONSUMPTION, TRANSFER ou PURCHASE)", example = "CONSUMPTION", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        RequisitionType type,

        @Schema(description = "Origem (ou destino, em transferências) da requisição", example = "Cozinha Central", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 20)
        String origin,

        @Schema(description = "Id da cozinha", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Integer kitchenId
) {
}
