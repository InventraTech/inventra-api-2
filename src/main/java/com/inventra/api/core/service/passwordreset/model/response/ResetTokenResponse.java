package com.inventra.api.core.service.passwordreset.model.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record ResetTokenResponse(
        @Schema(description = "Token para chamar /api/auth/password/reset; vale uma vez só", example = "q3Jx8v...")
        String resetToken,

        @Schema(description = "Segundos até o token expirar", example = "900")
        long expiresInSeconds
) {
}
