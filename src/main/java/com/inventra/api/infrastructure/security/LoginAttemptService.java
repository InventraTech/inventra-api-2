package com.inventra.api.infrastructure.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

// Freio contra força bruta no login: depois de MAX_FAILURES falhas seguidas para o mesmo e-mail a partir
// do mesmo IP, novas tentativas são recusadas (429) até a janela expirar. A chave inclui o IP para que
// um atacante não consiga bloquear o login da vítima de qualquer lugar. Em memória: a API roda numa
// instância só (Render); com várias instâncias, isso iria para o Redis.
@Component
public class LoginAttemptService {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginAttemptService() {
        this(Clock.systemUTC());
    }

    LoginAttemptService(Clock clock) {
        this.clock = clock;
    }

    public boolean isBlocked(String email, String ip) {
        Attempts current = attempts.get(key(email, ip));
        return current != null && !current.isExpired(clock.instant()) && current.failures() >= MAX_FAILURES;
    }

    public void registerFailure(String email, String ip) {
        Instant now = clock.instant();
        if (attempts.size() > MAX_TRACKED_KEYS) {
            attempts.values().removeIf(entry -> entry.isExpired(now));
        }
        attempts.merge(key(email, ip), new Attempts(1, now),
                (old, ignored) -> old.isExpired(now) ? new Attempts(1, now) : new Attempts(old.failures() + 1, old.firstFailure()));
    }

    public void registerSuccess(String email, String ip) {
        attempts.remove(key(email, ip));
    }

    private static String key(String email, String ip) {
        return (email == null ? "" : email.trim().toLowerCase(Locale.ROOT)) + "|" + ip;
    }

    private record Attempts(int failures, Instant firstFailure) {
        boolean isExpired(Instant now) {
            return firstFailure.plus(WINDOW).isBefore(now);
        }
    }
}
