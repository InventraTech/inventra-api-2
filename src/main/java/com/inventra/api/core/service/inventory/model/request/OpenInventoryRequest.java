package com.inventra.api.core.service.inventory.model.request;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// responsibleId é opcional: sem ele, o responsável é o usuário logado.
public record OpenInventoryRequest(
        @Schema(description = "Id da cozinha", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Integer kitchenId,

        @Schema(description = "Id (UUID) do responsável pelo inventário")
        UUID responsibleId,

        @Schema(description = "Observação", example = "Contagem mensal")
        @Size(max = 255)
        String note
) {
}
