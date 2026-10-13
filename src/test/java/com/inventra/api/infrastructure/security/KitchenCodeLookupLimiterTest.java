package com.inventra.api.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.inventra.api.infrastructure.exception.TooManyRequestsException;

class KitchenCodeLookupLimiterTest {

    private static final Instant START = Instant.parse("2026-10-09T12:00:00Z");

    @Test
    void blocksTheLookupAfterTheLimitInsideTheWindow() {
        KitchenCodeLookupLimiter limiter = new KitchenCodeLookupLimiter(3, Clock.fixed(START, ZoneOffset.UTC));
        UUID user = UUID.randomUUID();

        limiter.register(user);
        limiter.register(user);
        limiter.register(user);

        assertThatThrownBy(() -> limiter.register(user)).isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void eachUserHasItsOwnLimit() {
        KitchenCodeLookupLimiter limiter = new KitchenCodeLookupLimiter(1, Clock.fixed(START, ZoneOffset.UTC));

        limiter.register(UUID.randomUUID());

        assertThatCode(() -> limiter.register(UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    void windowResetsAfterAnHour() {
        UUID user = UUID.randomUUID();
        KitchenCodeLookupLimiter early = new KitchenCodeLookupLimiter(1, Clock.fixed(START, ZoneOffset.UTC));
        early.register(user);
        assertThatThrownBy(() -> early.register(user)).isInstanceOf(TooManyRequestsException.class);

        // mesma contagem, relógio uma hora e um minuto depois
        MutableClock clock = new MutableClock(START);
        KitchenCodeLookupLimiter limiter = new KitchenCodeLookupLimiter(1, clock);
        limiter.register(user);
        clock.advance(KitchenCodeLookupLimiter.WINDOW.plusMinutes(1));

        assertThatCode(() -> limiter.register(user)).doesNotThrowAnyException();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(java.time.Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
