package com.inventra.api.core.service.user.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        @Schema(description = "Nome", example = "Maria Souza")
        @Size(max = 120)
        @Pattern(regexp = ".*\\S.*", message = "não pode ser vazio")
        String name,

        @Schema(description = "Id da cozinha do usuário", example = "1")
        Integer kitchenId,

        @Schema(description = "Id do perfil", example = "1")
        Integer profileId
) {
}
