package com.inventra.api.core.service.auth;

import java.time.Instant;

// O que se guarda de cada refresh token (a chave é o hash do token, nunca o token em si).
// usedAt só aparece depois que o token foi trocado por um novo (rotação).
public record RefreshTokenData(
        String userId,
        String familyId,
        String passwordFingerprint,
        Instant usedAt
) {
}
