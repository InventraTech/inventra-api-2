package com.inventra.api.core.service.auth.model.request;

import com.inventra.api.core.domain.profile.AccessType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record GoogleLoginRequest(
        @Schema(description = "ID token do Firebase obtido pelo app", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String idToken,

        @Schema(description = "Tipo de acesso; só é usado (e exigido) no primeiro acesso, para criar a conta",
                example = "ESTOQUISTA")
        AccessType accessType
) {
}
