package com.inventra.api.core.service.profile.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @Schema(description = "Nome do perfil de acesso", example = "ESTOQUISTA")
        @Size(max = 50)
        @Pattern(regexp = ".*\\S.*", message = "não pode ser vazio")
        String accessType,

        @Schema(description = "Descrição", example = "Descrição opcional")
        @Size(max = 255)
        String description
) {
}
