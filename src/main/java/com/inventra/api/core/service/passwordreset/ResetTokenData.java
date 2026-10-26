package com.inventra.api.core.service.passwordreset;

// passwordFingerprint: impressão da senha na emissão (JwtService.passwordFingerprint); se a senha mudar
// antes do uso, o token não vale mais.
public record ResetTokenData(String userId, String passwordFingerprint) {
}
