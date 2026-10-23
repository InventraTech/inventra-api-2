package com.inventra.api.infrastructure.redis;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.inventra.api.core.service.passwordreset.PasswordResetStore;
import com.inventra.api.core.service.passwordreset.ResetTokenData;

import lombok.RequiredArgsConstructor;

// Segue o contrato de inventra-database-2 (redis/docs/KEYSPACE.md e redis/commands/auth/password-reset.redis):
// "inventra:password-reset:requests:{hashDoEmail}" conta pedidos na janela, "code:{userId}" guarda o hash do
// código e as tentativas, "token:{hashDoToken}" guarda o usuário e a impressão da senha.
@Component
@Profile("!test")
@RequiredArgsConstructor
public class RedisPasswordResetStore implements PasswordResetStore {

    static final String REQUESTS_PREFIX = "inventra:password-reset:requests:";
    static final String CODE_PREFIX = "inventra:password-reset:code:";
    static final String TOKEN_PREFIX = "inventra:password-reset:token:";

    // 0 = sem código pendente, 1 = bateu (consumido), 2 = não bateu, 3 = não bateu e esgotou (apagado)
    private static final DefaultRedisScript<Long> VERIFY_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
              return 0
            end
            local attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
            local max = tonumber(ARGV[2])
            if attempts > max then
              redis.call('DEL', KEYS[1])
              return 3
            end
            if redis.call('HGET', KEYS[1], 'codeHash') == ARGV[1] then
              redis.call('DEL', KEYS[1])
              return 1
            end
            if attempts >= max then
              redis.call('DEL', KEYS[1])
              return 3
            end
            return 2
            """, Long.class);

    private final StringRedisTemplate redis;

    @Override
    public long countRequest(String emailHash, Duration window) {
        String key = REQUESTS_PREFIX + emailHash;
        // em MULTI: o contador nasce já com TTL e nunca fica sem expirar
        List<Object> results = transaction(ops -> {
            ops.opsForValue().setIfAbsent(key, "0", window);
            ops.opsForValue().increment(key);
        });
        return ((Number) results.get(1)).longValue();
    }

    @Override
    public void saveCode(UUID userId, String codeHash, Duration ttl) {
        String key = CODE_PREFIX + userId;
        transaction(ops -> {
            ops.delete(key);
            ops.opsForHash().putAll(key, Map.of("codeHash", codeHash, "attempts", "0"));
            ops.expire(key, ttl);
        });
    }

    @Override
    public CodeCheck checkCode(UUID userId, String codeHash, int maxAttempts) {
        Long result = redis.execute(VERIFY_SCRIPT, List.of(CODE_PREFIX + userId), codeHash, String.valueOf(maxAttempts));
        return switch (result == null ? 0 : result.intValue()) {
            case 1 -> CodeCheck.MATCH;
            case 2 -> CodeCheck.MISMATCH;
            case 3 -> CodeCheck.EXHAUSTED;
            default -> CodeCheck.MISSING;
        };
    }

    @Override
    public void deleteCode(UUID userId) {
        redis.delete(CODE_PREFIX + userId);
    }

    @Override
    public void saveToken(String tokenHash, ResetTokenData data, Duration ttl) {
        String key = TOKEN_PREFIX + tokenHash;
        redis.opsForHash().putAll(key, Map.of("userId", data.userId(), "pwd", data.passwordFingerprint()));
        redis.expire(key, ttl);
    }

    @Override
    public Optional<ResetTokenData> consumeToken(String tokenHash) {
        String key = TOKEN_PREFIX + tokenHash;
        List<Object> results = transaction(ops -> {
            ops.opsForHash().entries(key);
            ops.delete(key);
        });
        Map<?, ?> fields = (Map<?, ?>) results.get(0);
        if (fields == null || fields.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ResetTokenData((String) fields.get("userId"), (String) fields.get("pwd")));
    }

    private List<Object> transaction(Consumer<RedisOperations<String, String>> commands) {
        return redis.execute(new SessionCallback<List<Object>>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> List<Object> execute(RedisOperations<K, V> operations) throws DataAccessException {
                RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                ops.multi();
                commands.accept(ops);
                return ops.exec();
            }
        });
    }
}
