package com.inventra.api.infrastructure.redis.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.inventra.api.core.service.productqueue.ProductRegistrationProcessor;
import com.inventra.api.core.service.productqueue.model.BarcodeRegistrationRequest;
import com.inventra.api.core.service.productqueue.model.ProductRegistrationJob;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.exception.QueueServiceException;

import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class RedisProductRegistrationQueueTest {
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private String prefix;
    private RedisProductRegistrationQueue queue;
    private final String token = UUID.randomUUID().toString();

    @BeforeAll
    static void connect() {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("REDIS_INTEGRATION_TESTS")),
                "Redis real exige REDIS_INTEGRATION_TESTS=true e ambiente dedicado de testes.");
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("REDIS_HOST") != null && System.getenv("REDIS_PORT") != null,
                "Configure REDIS_HOST e REDIS_PORT para testes de integração.");
        var server = new RedisStandaloneConfiguration(System.getenv("REDIS_HOST"),
                Integer.parseInt(System.getenv("REDIS_PORT")));
        server.setUsername(System.getenv("REDIS_USERNAME"));
        server.setPassword(System.getenv("REDIS_PASSWORD"));
        server.setDatabase(Integer.parseInt(System.getenv().getOrDefault("REDIS_DATABASE", "0")));
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(10));
        if (Boolean.parseBoolean(System.getenv().getOrDefault("REDIS_SSL", "true"))) client.useSsl();
        factory = new LettuceConnectionFactory(server, client.build());
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
    }

    @BeforeEach
    void namespace() {
        prefix = "inventra:test:product-queue:" + UUID.randomUUID();
        queue = new RedisProductRegistrationQueue(redis, JsonMapper.builder().build(), prefix);
    }

    @AfterEach
    void cleanOwnKeys() {
        var keys = redis.keys(prefix + ":*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    @AfterAll
    static void disconnect() { if (factory != null) factory.destroy(); }

    @Test
    void enqueuesAndConsumesMultipleItemsInFifoOrder() {
        var jobs = List.of(job("7891234567890"), job("7891234567891"), job("7891234567892"));
        jobs.forEach(queue::enqueue);
        assertThat(redis.opsForList().range(prefix + ":ready", 0, -1))
                .containsExactly(jobs.get(2).eventId().toString(), jobs.get(1).eventId().toString(), jobs.get(0).eventId().toString());
        assertThat(queue.acquire(token)).isTrue();
        queue.recover(token);
        for (var job : jobs) {
            assertThat(queue.take(token)).isEqualTo(job.eventId());
            assertThat(queue.find(job.eventId())).contains(job);
        }
    }

    @Test
    void workerCompletesJobsSequentiallyAndLogsStartAndEnd(CapturedOutput output) {
        var first = job("7891234567890");
        var second = job("7891234567891");
        queue.enqueue(first);
        queue.enqueue(second);
        var processor = mock(ProductRegistrationProcessor.class);
        when(processor.process(any())).thenReturn(101, 102);
        var consumer = new ProductRegistrationConsumer(queue, processor, true);
        try {
            consumer.start();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(queue.find(first.eventId()).orElseThrow().productId()).isEqualTo(101);
                assertThat(queue.find(second.eventId()).orElseThrow().productId()).isEqualTo(102);
            });
        } finally { consumer.stop(); }
        assertThat(output).contains("Cadastro iniciado eventId=" + first.eventId(),
                "Cadastro finalizado eventId=" + first.eventId(), "timestamp=");
        assertThat(redis.opsForZSet().size(prefix + ":pending")).isZero();
        assertThat(redis.getExpire(prefix + ":job:" + first.eventId())).isBetween(1L, 604800L);
    }

    @Test
    void failureMovesOriginalPayloadToDeadLetterAndNextJobStillSucceeds(CapturedOutput output) {
        var first = job("7891234567890");
        var second = job("7891234567891");
        queue.enqueue(first);
        queue.enqueue(second);
        assertThat(queue.acquire(token)).isTrue();
        queue.recover(token);
        var processor = mock(ProductRegistrationProcessor.class);
        when(processor.process(any())).thenThrow(new ResourceNotFoundException("Detalhe privado"))
                .thenReturn(102);
        var consumer = new ProductRegistrationConsumer(queue, processor, false);
        consumer.process(queue.take(token), token);
        consumer.process(queue.take(token), token);
        assertThat(queue.find(first.eventId()).orElseThrow().status()).isEqualTo(ProductRegistrationJob.Status.FAILED);
        assertThat(queue.find(second.eventId()).orElseThrow().productId()).isEqualTo(102);
        assertThat(redis.opsForList().range(prefix + ":errors", 0, -1)).singleElement()
                .asString().contains(first.eventId().toString(), "REFERENCE_NOT_FOUND", first.request().barcode())
                .doesNotContain("Detalhe privado");
        assertThat(output).contains("status=FAILED", "status=COMPLETED").doesNotContain("Detalhe privado");
    }

    @Test
    void restartRecoversPoppedAndProcessingItemsInOriginalOrder() {
        var first = job("7891234567890");
        var second = job("7891234567891");
        queue.enqueue(first);
        queue.enqueue(second);
        assertThat(queue.acquire(token)).isTrue();
        queue.recover(token);
        assertThat(queue.take(token)).isEqualTo(first.eventId());
        assertThat(queue.update(first.processing(), token)).isTrue();
        queue.release(token);
        // Nova instância da aplicação utiliza os mesmos dados persistidos no Redis.
        var restarted = new RedisProductRegistrationQueue(redis, JsonMapper.builder().build(), prefix);
        String newToken = UUID.randomUUID().toString();
        assertThat(restarted.acquire(newToken)).isTrue();
        restarted.recover(newToken);
        assertThat(restarted.take(newToken)).isEqualTo(first.eventId());
        assertThat(restarted.take(newToken)).isEqualTo(second.eventId());
        assertThat(redis.getExpire(prefix + ":job:" + first.eventId())).isEqualTo(-1L);
    }

    @Test
    void expiredConsumerCannotAcknowledgeOrReleaseSuccessorLease() {
        var job = job("7891234567890");
        queue.enqueue(job);
        assertThat(queue.acquire(token)).isTrue();
        assertThat(queue.acquire("another-worker")).isFalse();
        queue.release(token);
        assertThat(queue.acquire("another-worker")).isTrue();
        assertThat(queue.update(job.completed(1), token)).isFalse();
        queue.release(token);
        assertThat(queue.renew("another-worker")).isTrue();
    }

    @Test
    void redisAcknowledgementFailurePreservesPendingJobInsteadOfCreatingDeadLetter() {
        var job = job("7891234567890");
        queue.enqueue(job);
        assertThat(queue.acquire(token)).isTrue();
        queue.recover(token);
        var processor = mock(ProductRegistrationProcessor.class);
        when(processor.process(any())).thenAnswer(invocation -> { queue.release(token); return 77; });
        var consumer = new ProductRegistrationConsumer(queue, processor, false);
        org.junit.jupiter.api.Assertions.assertThrows(QueueServiceException.class,
                () -> consumer.process(queue.take(token), token));
        assertThat(redis.opsForZSet().size(prefix + ":pending")).isEqualTo(1);
        assertThat(redis.opsForList().size(prefix + ":errors")).isZero();
        assertThat(queue.acquire("restarted")).isTrue();
        queue.recover("restarted");
        assertThat(queue.take("restarted")).isEqualTo(job.eventId());
    }

    @Test
    void oldConsumerCannotTakeItemsFromSuccessorListAndReadyLeaseIsRenewed() {
        var job = job("7891234567890");
        queue.enqueue(job);
        assertThat(queue.acquire(token)).isTrue();
        queue.recover(token);
        // Simula expiração da concessão, preservando a antiga lista de consumo.
        redis.delete(prefix + ":consumer-lock");
        String successor = "successor";
        assertThat(queue.acquire(successor)).isTrue();
        queue.recover(successor);
        assertThat(queue.renew(successor)).isTrue();
        assertThat(redis.getExpire(prefix + ":ready:" + successor)).isBetween(1L, 30L);
        queue.take(token);
        assertThat(queue.take(successor)).isEqualTo(job.eventId());
        assertThat(queue.update(job.completed(1), token)).isFalse();
    }

    @Test
    void restartRecoversItemPoppedBeforeProcessingStatusWasStored() {
        var first = job("7891234567890");
        var second = job("7891234567891");
        queue.enqueue(first);
        queue.enqueue(second);
        assertThat(queue.acquire(token)).isTrue();
        queue.recover(token);
        assertThat(queue.take(token)).isEqualTo(first.eventId());

        // Simula queda exatamente entre BRPOP e a atualização para PROCESSING.
        queue.release(token);
        String successor = "successor-after-pop";
        assertThat(queue.acquire(successor)).isTrue();
        queue.recover(successor);

        assertThat(queue.take(successor)).isEqualTo(first.eventId());
        assertThat(queue.take(successor)).isEqualTo(second.eventId());
    }

    @Test
    void onlyLeaseOwnerCanRecoverTheQueue() {
        var job = job("7891234567890");
        queue.enqueue(job);
        assertThat(queue.acquire(token)).isTrue();
        queue.recover(token);

        org.junit.jupiter.api.Assertions.assertThrows(QueueServiceException.class,
                () -> queue.recover("consumer-without-lease"));
        assertThat(queue.take(token)).isEqualTo(job.eventId());
    }

    private static ProductRegistrationJob job(String barcode) {
        return ProductRegistrationJob.queued(UUID.randomUUID(),
                new BarcodeRegistrationRequest("Arroz", "Inventra", null, 1, barcode, null));
    }

    @Test void deadLetterHasBoundedSizeAndInactivityExpiry() {
        redis.opsForList().leftPushAll(prefix + ":errors", java.util.Collections.nCopies(1000, "old"));
        var job = job("7891234567890");
        queue.enqueue(job);
        assertThat(queue.acquire(token)).isTrue();
        assertThat(queue.update(job.failed("PROCESSING_ERROR"), token)).isTrue();
        assertThat(redis.opsForList().size(prefix + ":errors")).isEqualTo(1000);
        assertThat(redis.getExpire(prefix + ":errors")).isBetween(1L, 604800L);
    }
}
