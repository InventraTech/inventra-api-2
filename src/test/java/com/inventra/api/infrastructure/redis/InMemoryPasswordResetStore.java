package com.inventra.api.infrastructure.redis;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.inventra.api.core.service.passwordreset.PasswordResetStore;
import com.inventra.api.core.service.passwordreset.ResetTokenData;

// Substitui o Redis nos testes (perfil "test"): mesmo contrato, sem rede. Expiração não é simulada.
@Component
@Profile("test")
public class InMemoryPasswordResetStore implements PasswordResetStore {

    private final Map<String, Long> requests = new ConcurrentHashMap<>();
    private final Map<UUID, Code> codes = new ConcurrentHashMap<>();
    private final Map<String, ResetTokenData> tokens = new ConcurrentHashMap<>();

    @Override
    public long countRequest(String emailHash, Duration window) {
        return requests.merge(emailHash, 1L, Long::sum);
    }

    @Override
    public void saveCode(UUID userId, String codeHash, Duration ttl) {
        codes.put(userId, new Code(codeHash, 0));
    }

    @Override
    public synchronized CodeCheck checkCode(UUID userId, String codeHash, int maxAttempts) {
        Code code = codes.get(userId);
        if (code == null) {
            return CodeCheck.MISSING;
        }
        int attempts = code.attempts() + 1;
        if (attempts <= maxAttempts && code.codeHash().equals(codeHash)) {
            codes.remove(userId);
            return CodeCheck.MATCH;
        }
        if (attempts >= maxAttempts) {
            codes.remove(userId);
            return CodeCheck.EXHAUSTED;
        }
        codes.put(userId, new Code(code.codeHash(), attempts));
        return CodeCheck.MISMATCH;
    }

    @Override
    public void deleteCode(UUID userId) {
        codes.remove(userId);
    }

    @Override
    public void saveToken(String tokenHash, ResetTokenData data, Duration ttl) {
        tokens.put(tokenHash, data);
    }

    @Override
    public Optional<ResetTokenData> consumeToken(String tokenHash) {
        return Optional.ofNullable(tokens.remove(tokenHash));
    }

    public boolean hasCode(UUID userId) {
        return codes.containsKey(userId);
    }

    public void clear() {
        requests.clear();
        codes.clear();
        tokens.clear();
    }

    private record Code(String codeHash, int attempts) {
    }
}
