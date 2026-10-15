package com.inventra.api.infrastructure.redis.ranking;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.inventra.api.core.service.ranking.*;

@EnabledIfEnvironmentVariable(named = "REDIS_RANKING_TESTS", matches = "true")
class RedisExpirationRankingTest {
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate template;
    private RedisExpirationRanking ranking;
    private String prefix;

    @BeforeAll static void connect() {
        // Variáveis separadas: nunca utiliza as credenciais Redis da aplicação.
        var config = new RedisStandaloneConfiguration(
                System.getenv().getOrDefault("RANKING_TEST_REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("RANKING_TEST_REDIS_PORT", "16379")));
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(5)).build();
        factory = new LettuceConnectionFactory(config, client);
        factory.afterPropertiesSet();
        factory.start();
        template = new StringRedisTemplate(factory);
    }

    @BeforeEach void namespace() {
        prefix = "inventra:test:ranking:" + UUID.randomUUID();
        ranking = new RedisExpirationRanking(template, prefix, 60);
    }

    @AfterEach void clean() {
        template.delete(List.of(prefix + ":{1}", prefix + ":{2}"));
    }

    @AfterAll static void disconnect() {
        if (factory != null) factory.destroy();
    }

    @Test void sortsAscendingWithNumericTieBreakAndKitchenIsolation() {
        ranking.replace(1, List.of(row(12, 20002), row(10, 20000), row(2, 20000)));
        ranking.replace(2, List.of(row(99, 19999)));
        assertThat(ranking.top(1, 2).orElseThrow()).extracting(RankingEntry::batchId).containsExactly(2, 10);
        assertThat(ranking.top(1, 3).orElseThrow()).extracting(RankingEntry::score)
                .containsExactly(20000L, 20000L, 20002L);
        assertThat(ranking.top(2, 10).orElseThrow()).extracting(RankingEntry::batchId).containsExactly(99);
        assertThat(template.type(prefix + ":{1}").code()).isEqualTo("zset");
    }

    @Test void replaceRemovesDepletedBatchesAndSupportsAnEmptyRanking() {
        ranking.replace(1, List.of(row(1, 20000), row(2, 20001)));
        ranking.replace(1, List.of(row(2, 20001)));
        assertThat(ranking.top(1, 10).orElseThrow()).extracting(RankingEntry::batchId).containsExactly(2);
        ranking.replace(1, List.of());
        assertThat(ranking.top(1, 10)).hasValue(List.of());
    }

    @Test void expirationAndRedisLossAllowReconstruction() {
        ranking = new RedisExpirationRanking(template, prefix, 1);
        ranking.replace(1, List.of(row(1, 20000)));
        assertThat(template.getExpire(prefix + ":{1}")).isBetween(0L, 1L);
        await().atMost(Duration.ofSeconds(5)).until(() -> ranking.top(1, 10).isEmpty());
        ranking.replace(1, List.of(row(2, 20001)));
        assertThat(ranking.top(1, 10).orElseThrow()).extracting(RankingEntry::batchId).containsExactly(2);
        template.delete(prefix + ":{1}");
        assertThat(ranking.top(1, 10)).isEmpty();
    }

    @Test void concurrentReadersNeverObservePartialOrMixedSnapshots() throws Exception {
        var a = IntStream.rangeClosed(1, 40).mapToObj(id -> row(id, 20000 + id)).toList();
        var b = IntStream.rangeClosed(101, 140).mapToObj(id -> row(id, 20000 + id)).toList();
        ranking.replace(1, a);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = List.<Callable<Void>>of(
                    () -> { for (int i = 0; i < 50; i++) ranking.replace(1, a); return null; },
                    () -> { for (int i = 0; i < 50; i++) ranking.replace(1, b); return null; },
                    () -> { for (int i = 0; i < 100; i++) {
                        var ids = ranking.top(1, 100).orElseThrow().stream().map(RankingEntry::batchId).toList();
                        assertThat(ids.equals(a.stream().map(RankingCandidate::batchId).toList())
                                || ids.equals(b.stream().map(RankingCandidate::batchId).toList())).isTrue();
                    } return null; });
            for (var future : executor.invokeAll(tasks)) future.get(15, TimeUnit.SECONDS);
        }
    }

    private RankingCandidate row(int id, long score) {
        return new RankingCandidate(id, LocalDate.ofEpochDay(score));
    }

    @Test void invalidReplacementCannotDestroyPublishedRanking() {
        ranking.replace(1, List.of(row(1, 20000)));
        var script = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>();
        script.setLocation(new org.springframework.core.io.ClassPathResource("redis/ranking/replace.lua"));
        script.setResultType(Long.class);
        assertThatThrownBy(() -> template.execute(script,
                List.of(prefix + ":{1}", prefix + ":{1}:build:invalid"),
                "60", "~empty", "0000000002", "20001", "0000000003", "invalid"))
                .isInstanceOf(RuntimeException.class);
        assertThat(ranking.top(1, 10).orElseThrow()).extracting(RankingEntry::batchId).containsExactly(1);
    }
}
