package com.inventra.api.infrastructure.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.inventra.api.core.service.auth.RefreshTokenData;
import com.inventra.api.core.service.auth.RefreshTokenStore;

// Substitui o Redis nos testes (perfil "test"): mesmo contrato, sem rede. Expiração não é simulada.
@Component
@Profile("test")
public class InMemoryRefreshTokenStore implements RefreshTokenStore {

    private final Map<String, RefreshTokenData> tokens = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> families = new ConcurrentHashMap<>();

    @Override
    public void save(String tokenHash, RefreshTokenData data, Duration ttl) {
        tokens.put(tokenHash, data);
        families.computeIfAbsent(data.familyId(), key -> ConcurrentHashMap.newKeySet()).add(tokenHash);
    }

    @Override
    public Optional<RefreshTokenData> find(String tokenHash) {
        return Optional.ofNullable(tokens.get(tokenHash));
    }

    @Override
    public void markUsed(String tokenHash, Instant at) {
        tokens.computeIfPresent(tokenHash, (key, data) -> data.usedAt() != null ? data
                : new RefreshTokenData(data.userId(), data.familyId(), data.passwordFingerprint(), at));
    }

    // Só para testes: força a data do primeiro uso (para simular um token trocado há tempo).
    public void forceUsedAt(String tokenHash, Instant at) {
        tokens.computeIfPresent(tokenHash, (key, data) ->
                new RefreshTokenData(data.userId(), data.familyId(), data.passwordFingerprint(), at));
    }

    @Override
    public void revokeFamily(String familyId) {
        Set<String> hashes = families.remove(familyId);
        if (hashes != null) {
            new HashSet<>(hashes).forEach(tokens::remove);
        }
    }
}
