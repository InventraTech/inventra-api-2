package com.inventra.api.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class LoginAttemptServiceTest {

    private static final String EMAIL = "maria@inventra.com";
    private static final String IP = "10.0.0.1";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-02T12:00:00Z"));
    private final LoginAttemptService service = new LoginAttemptService(clock);

    @Test
    void blocksAfterMaxFailures() {
        failTimes(LoginAttemptService.MAX_FAILURES - 1);
        assertThat(service.isBlocked(EMAIL, IP)).isFalse();

        service.registerFailure(EMAIL, IP);
        assertThat(service.isBlocked(EMAIL, IP)).isTrue();
    }

    @Test
    void unblocksAfterWindowExpires() {
        failTimes(LoginAttemptService.MAX_FAILURES);

        clock.advance(LoginAttemptService.WINDOW.plusSeconds(1));

        assertThat(service.isBlocked(EMAIL, IP)).isFalse();
    }

    @Test
    void successResetsTheCounter() {
        failTimes(LoginAttemptService.MAX_FAILURES - 1);
        service.registerSuccess(EMAIL, IP);
        service.registerFailure(EMAIL, IP);

        assertThat(service.isBlocked(EMAIL, IP)).isFalse();
    }

    // A chave inclui o IP: um atacante não consegue bloquear o login da vítima a partir de outro lugar.
    @Test
    void failuresFromAnotherIpDoNotBlockTheVictim() {
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            service.registerFailure(EMAIL, "203.0.113.9");
        }

        assertThat(service.isBlocked(EMAIL, IP)).isFalse();
        assertThat(service.isBlocked(EMAIL, "203.0.113.9")).isTrue();
    }

    @Test
    void emailIsCaseInsensitive() {
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            service.registerFailure(EMAIL.toUpperCase(), IP);
        }

        assertThat(service.isBlocked(EMAIL, IP)).isTrue();
    }

    private void failTimes(int times) {
        for (int i = 0; i < times; i++) {
            service.registerFailure(EMAIL, IP);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
