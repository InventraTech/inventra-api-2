package com.inventra.api.core.service.auth.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @Schema(description = "E-mail", example = "maria@inventra.com", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Email
        String email,

        @Schema(description = "Senha", example = "Senha@123", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String password
) {
}
