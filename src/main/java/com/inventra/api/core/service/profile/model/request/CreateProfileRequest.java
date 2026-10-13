package com.inventra.api.core.service.profile.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProfileRequest(
        @Schema(description = "Nome do perfil de acesso", example = "ESTOQUISTA", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 50)
        String accessType,

        @Schema(description = "Descrição do perfil", example = "Acesso ao estoque")
        @Size(max = 255)
        String description
) {
}
