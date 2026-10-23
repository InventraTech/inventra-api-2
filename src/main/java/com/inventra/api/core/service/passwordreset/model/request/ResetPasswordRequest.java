package com.inventra.api.core.service.passwordreset.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @Schema(description = "Token de redefinição: o devolvido por /verify ou o parâmetro token do link do e-mail",
                example = "q3Jx8v...", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 200)
        String resetToken,

        @Schema(description = "Nova senha (mínimo 8 caracteres)", example = "NovaSenha@456",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(min = 8, max = 100)
        String newPassword
) {
}
