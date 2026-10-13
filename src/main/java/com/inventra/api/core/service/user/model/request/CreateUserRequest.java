package com.inventra.api.core.service.user.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @Schema(description = "Nome", example = "Maria Souza", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 120)
        String name,

        @Schema(description = "E-mail", example = "maria@inventra.com", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Email
        @Size(max = 150)
        String email,

        @Schema(description = "Senha (mínimo 8 caracteres)", example = "Senha@123", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(min = 8, max = 100)
        String password,

        @Schema(description = "Id da cozinha do usuário (opcional)", example = "1")
        Integer kitchenId,

        @Schema(description = "Id do perfil", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Integer profileId
) {
}
