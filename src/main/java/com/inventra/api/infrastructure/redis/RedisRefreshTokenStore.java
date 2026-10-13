package com.inventra.api.infrastructure.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.inventra.api.core.service.auth.RefreshTokenData;
import com.inventra.api.core.service.auth.RefreshTokenStore;

import lombok.RequiredArgsConstructor;

// Cada token é um hash em "rt:{hash do token}" com TTL; a família é um set "rt:fam:{id}" com os hashes dela,
// para revogar tudo de uma vez. Só valores simples (strings), sem serializer de objetos.
@Component
@Profile("!test")
@RequiredArgsConstructor
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private static final String TOKEN_PREFIX = "rt:";
    private static final String FAMILY_PREFIX = "rt:fam:";

    private final StringRedisTemplate redis;

    @Override
    public void save(String tokenHash, RefreshTokenData data, Duration ttl) {
        String key = TOKEN_PREFIX + tokenHash;
        redis.opsForHash().putAll(key, Map.of(
                "userId", data.userId(),
                "familyId", data.familyId(),
                "pwd", data.passwordFingerprint()));
        redis.expire(key, ttl);

        String familyKey = FAMILY_PREFIX + data.familyId();
        redis.opsForSet().add(familyKey, tokenHash);
        redis.expire(familyKey, ttl);
    }

    @Override
    public Optional<RefreshTokenData> find(String tokenHash) {
        Map<Object, Object> fields = redis.opsForHash().entries(TOKEN_PREFIX + tokenHash);
        if (fields.isEmpty()) {
            return Optional.empty();
        }
        Object usedAt = fields.get("usedAt");
        return Optional.of(new RefreshTokenData(
                (String) fields.get("userId"),
                (String) fields.get("familyId"),
                (String) fields.get("pwd"),
                usedAt == null ? null : Instant.ofEpochMilli(Long.parseLong((String) usedAt))));
    }

    @Override
    public void markUsed(String tokenHash, Instant at) {
        redis.opsForHash().putIfAbsent(TOKEN_PREFIX + tokenHash, "usedAt", String.valueOf(at.toEpochMilli()));
    }

    @Override
    public void revokeFamily(String familyId) {
        String familyKey = FAMILY_PREFIX + familyId;
        Set<String> hashes = redis.opsForSet().members(familyKey);
        if (hashes != null && !hashes.isEmpty()) {
            redis.delete(hashes.stream().map(hash -> TOKEN_PREFIX + hash).toList());
        }
        redis.delete(familyKey);
    }
}
