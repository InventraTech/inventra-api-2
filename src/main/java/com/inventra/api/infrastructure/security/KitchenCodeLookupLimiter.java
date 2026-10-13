package com.inventra.api.infrastructure.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.inventra.api.infrastructure.exception.TooManyRequestsException;

// Freio contra adivinhação de códigos de cozinha: a busca por código é aberta a qualquer usuário
// autenticado (inclusive quem ainda não tem cozinha), então cada usuário tem um número limitado de
// consultas por janela. Em memória, como o LoginAttemptService: a API roda numa instância só.
@Component
public class KitchenCodeLookupLimiter {

    static final Duration WINDOW = Duration.ofHours(1);
    private static final int MAX_TRACKED_USERS = 10_000;

    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();
    private final int maxPerWindow;
    private final Clock clock;

    @Autowired
    public KitchenCodeLookupLimiter(@Value("${app.kitchen-code-lookup.max-per-hour:10}") int maxPerWindow) {
        this(maxPerWindow, Clock.systemUTC());
    }

    KitchenCodeLookupLimiter(int maxPerWindow, Clock clock) {
        this.maxPerWindow = maxPerWindow;
        this.clock = clock;
    }

    // Conta uma consulta; lança 429 quando o usuário passa do limite da janela.
    public void register(UUID userId) {
        Instant now = clock.instant();
        if (windows.size() > MAX_TRACKED_USERS) {
            windows.values().removeIf(window -> window.isExpired(now));
        }
        Window updated = windows.merge(userId, new Window(1, now),
                (old, ignored) -> old.isExpired(now) ? new Window(1, now) : new Window(old.count() + 1, old.start()));
        if (updated.count() > maxPerWindow) {
            throw new TooManyRequestsException("Muitas consultas de código de cozinha. Aguarde e tente novamente.");
        }
    }

    private record Window(int count, Instant start) {
        boolean isExpired(Instant now) {
            return start.plus(WINDOW).isBefore(now);
        }
    }
}
