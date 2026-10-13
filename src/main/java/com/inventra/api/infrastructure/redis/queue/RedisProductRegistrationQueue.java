package com.inventra.api.infrastructure.redis.queue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.inventra.api.core.service.productqueue.model.ProductRegistrationJob;
import com.inventra.api.infrastructure.exception.QueueServiceException;

import tools.jackson.databind.json.JsonMapper;

@Component
public class RedisProductRegistrationQueue {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final DefaultRedisScript<Long> ENQUEUE = script("enqueue");
    private static final DefaultRedisScript<Long> RECOVER = script("recover");
    private static final DefaultRedisScript<Long> UPDATE = script("update");
    private static final DefaultRedisScript<Long> LEASE_SCRIPT = script("lease");

    private final StringRedisTemplate redis;
    private final JsonMapper mapper;
    private final String prefix;

    public RedisProductRegistrationQueue(StringRedisTemplate redis, JsonMapper mapper,
            @Value("${app.redis.product-queue.key-prefix:inventra:product-registration}") String prefix) {
        this.redis = redis;
        this.mapper = mapper;
        this.prefix = prefix;
    }

    public void enqueue(ProductRegistrationJob job) {
        Long result = redis.execute(ENQUEUE, List.of(key("ready"), key("pending"), key("sequence"), jobKey(job.eventId()), key("consumer-lock")),
                job.eventId().toString(), mapper.writeValueAsString(job));
        if (!Long.valueOf(1).equals(result)) {
            throw new QueueServiceException("Não foi possível enfileirar o cadastro.");
        }
    }

    public Optional<ProductRegistrationJob> find(UUID eventId) {
        String json = redis.opsForValue().get(jobKey(eventId));
        return Optional.ofNullable(json).map(value -> mapper.readValue(value, ProductRegistrationJob.class));
    }

    public boolean acquire(String token) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key("consumer-lock"), token, LEASE));
    }

    public boolean renew(String token) {
        return Long.valueOf(1).equals(redis.execute(LEASE_SCRIPT, List.of(key("consumer-lock"), readyKey(token)),
                token, Long.toString(LEASE.toSeconds())));
    }

    public void release(String token) {
        redis.execute(LEASE_SCRIPT, List.of(key("consumer-lock"), readyKey(token)), token, "0");
        redis.delete(readyKey(token));
    }

    /** Reconstrói a lista em ordem original, incluindo itens retirados por BRPOP antes de uma queda. */
    public void recover(String token) {
        Long result = redis.execute(RECOVER, List.of(key("consumer-lock"), readyKey(token), key("pending"), key("ready")), token);
        if (result == null || result < 0) {
            throw new QueueServiceException("Consumer perdeu a concessão de processamento.");
        }
    }

    public UUID take(String token) {
        // Uma concessão antiga nunca retira itens da lista reconstruída por sua sucessora.
        String value = redis.opsForList().rightPop(readyKey(token), Duration.ofSeconds(2));
        return value == null ? null : UUID.fromString(value);
    }

    public boolean update(ProductRegistrationJob job, String token) {
        boolean terminal = job.status() == ProductRegistrationJob.Status.COMPLETED
                || job.status() == ProductRegistrationJob.Status.FAILED;
        return Long.valueOf(1).equals(redis.execute(UPDATE,
                List.of(key("consumer-lock"), jobKey(job.eventId()), key("pending"), key("errors")),
                token, job.eventId().toString(), mapper.writeValueAsString(job),
                terminal ? "1" : "0", job.status() == ProductRegistrationJob.Status.FAILED ? "1" : "0"));
    }

    private String jobKey(UUID eventId) { return key("job:" + eventId); }
    private String readyKey(String token) { return key("ready:" + token); }
    private String key(String suffix) { return prefix + ":" + suffix; }

    private static DefaultRedisScript<Long> script(String name) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/product-queue/" + name + ".lua"));
        script.setResultType(Long.class);
        return script;
    }
}
