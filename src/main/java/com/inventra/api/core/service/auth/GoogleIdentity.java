package com.inventra.api.core.service.auth;

// O que a API usa do ID token do Firebase: só identidade. Cargo, cozinha e sessão continuam sendo do backend.
public record GoogleIdentity(
        String uid,
        String email,
        boolean emailVerified,
        String name
) {
}
