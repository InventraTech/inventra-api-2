package com.inventra.api.core.service.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

// Porta de armazenamento dos refresh tokens. Em produção é o Redis (RedisRefreshTokenStore); os testes
// usam uma implementação em memória.
public interface RefreshTokenStore {

    void save(String tokenHash, RefreshTokenData data, Duration ttl);

    Optional<RefreshTokenData> find(String tokenHash);

    // Marca o token como usado; só o primeiro uso é gravado.
    void markUsed(String tokenHash, Instant at);

    // Invalida todos os tokens da família (cadeia de rotações de um mesmo login).
    void revokeFamily(String familyId);
}
