package com.inventra.api.core.service.auth.model.request;

import com.inventra.api.core.domain.profile.AccessType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
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

        @Schema(description = "Tipo de acesso do usuário", example = "ESTOQUISTA", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        AccessType accessType
) {
}
