package com.inventra.api.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.inventra.api.core.service.auth.RefreshTokenData;
import com.inventra.api.core.service.kitchenaccess.KitchenAccessRequest;
import com.inventra.api.core.service.kitchenaccess.KitchenAccessRequestStatus;

// Exercita as implementações de Redis de verdade. Só roda se houver um Redis em REDIS_TEST_HOST:REDIS_TEST_PORT
// (padrão localhost:6379), por exemplo: docker run -d -p 6379:6379 redis:7. Sem Redis, é pulado.
class RedisStoresTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        String host = System.getenv().getOrDefault("REDIS_TEST_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_TEST_PORT", "6379"));
        try {
            factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
            factory.afterPropertiesSet();
            redis = new StringRedisTemplate(factory);
            redis.afterPropertiesSet();
            redis.getConnectionFactory().getConnection().ping();
        } catch (RuntimeException ex) {
            assumeTrue(false, "Redis indisponível em " + host + ":" + port);
        }
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    void refreshTokenStoreSavesFindsMarksUsedAndRevokesTheFamily() {
        RedisRefreshTokenStore store = new RedisRefreshTokenStore(redis);
        String family = UUID.randomUUID().toString();
        String first = "h1-" + UUID.randomUUID();
        String second = "h2-" + UUID.randomUUID();
        store.save(first, new RefreshTokenData("u1", family, "fp", null), Duration.ofMinutes(5));
        store.save(second, new RefreshTokenData("u1", family, "fp", null), Duration.ofMinutes(5));

        assertThat(store.find(first)).contains(new RefreshTokenData("u1", family, "fp", null));
        assertThat(redis.getExpire("rt:" + first)).isPositive();

        Instant at = Instant.ofEpochMilli(1_700_000_000_000L);
        store.markUsed(first, at);
        store.markUsed(first, at.plusSeconds(99));
        assertThat(store.find(first).orElseThrow().usedAt()).isEqualTo(at);

        store.revokeFamily(family);
        assertThat(store.find(first)).isEmpty();
        assertThat(store.find(second)).isEmpty();
        assertThat(redis.hasKey("rt:fam:" + family)).isFalse();
    }

    @Test
    void kitchenAccessRequestStoreDecidesOnceAndDropsTheTtl() {
        RedisKitchenAccessRequestStore store = new RedisKitchenAccessRequestStore(redis);
        int kitchenId = 900_000 + (int) (Math.random() * 90_000);
        UUID userId = UUID.randomUUID();
        UUID supervisor = UUID.randomUUID();
        KitchenAccessRequest request = new KitchenAccessRequest(UUID.randomUUID().toString(), userId, "Maria",
                "maria@inventra.com", kitchenId, "Cozinha", KitchenAccessRequestStatus.PENDING,
                Instant.ofEpochMilli(Instant.now().toEpochMilli()), null, null, null);

        store.savePending(request, Duration.ofDays(7));

        assertThat(store.find(request.id())).contains(request);
        assertThat(store.findLatestByUser(userId)).contains(request);
        assertThat(store.listByKitchen(kitchenId)).containsExactly(request);
        assertThat(redis.getExpire("kar:" + request.id())).isPositive();

        Instant at = Instant.ofEpochMilli(Instant.now().toEpochMilli());
        assertThat(store.decide(request.id(), KitchenAccessRequestStatus.APPROVED, supervisor, null, at)).isTrue();
        // a segunda decisão perde: já não está pendente
        assertThat(store.decide(request.id(), KitchenAccessRequestStatus.REJECTED, supervisor, "tarde", at)).isFalse();

        KitchenAccessRequest decided = store.find(request.id()).orElseThrow();
        assertThat(decided.status()).isEqualTo(KitchenAccessRequestStatus.APPROVED);
        assertThat(decided.decidedBy()).isEqualTo(supervisor);
        assertThat(decided.decidedAt()).isEqualTo(at);
        assertThat(decided.reason()).isNull();
        // decidido não expira mais: vira histórico
        assertThat(redis.getExpire("kar:" + request.id())).isEqualTo(-1L);
    }

    @Test
    void expiredRequestsAreDroppedFromTheKitchenListing() {
        RedisKitchenAccessRequestStore store = new RedisKitchenAccessRequestStore(redis);
        int kitchenId = 800_000 + (int) (Math.random() * 90_000);
        KitchenAccessRequest request = new KitchenAccessRequest(UUID.randomUUID().toString(), UUID.randomUUID(), "Joao",
                "joao@inventra.com", kitchenId, "Cozinha", KitchenAccessRequestStatus.PENDING,
                Instant.ofEpochMilli(Instant.now().toEpochMilli()), null, null, null);
        store.savePending(request, Duration.ofDays(7));

        redis.delete("kar:" + request.id());

        assertThat(store.listByKitchen(kitchenId)).isEmpty();
        assertThat(redis.opsForZSet().zCard("kar:kitchen:" + kitchenId)).isZero();
    }
}
