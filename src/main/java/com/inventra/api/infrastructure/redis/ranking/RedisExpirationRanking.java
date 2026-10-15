package com.inventra.api.infrastructure.redis.ranking;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.inventra.api.core.service.ranking.RankingCandidate;
import com.inventra.api.core.service.ranking.RankingEntry;

@Component
public class RedisExpirationRanking {
    private static final String EMPTY_MARKER = "~empty";
    private final StringRedisTemplate redis;
    private final String prefix;
    private final long ttlSeconds;
    private final DefaultRedisScript<Long> replaceScript = new DefaultRedisScript<>();

    public RedisExpirationRanking(StringRedisTemplate redis,
            @Value("${app.redis.ranking.key-prefix:inventra:ranking:expiration}") String prefix,
            @Value("${app.redis.ranking.ttl-seconds:60}") long ttlSeconds) {
        if (ttlSeconds < 1) throw new IllegalArgumentException("Ranking TTL deve ser positivo.");
        this.redis = redis;
        this.prefix = prefix;
        this.ttlSeconds = ttlSeconds;
        replaceScript.setLocation(new ClassPathResource("redis/ranking/replace.lua"));
        replaceScript.setResultType(Long.class);
    }

    public void replace(Integer kitchenId, List<RankingCandidate> batches) {
        List<String> args = new ArrayList<>();
        args.add(Long.toString(ttlSeconds));
        args.add(EMPTY_MARKER);
        for (var batch : batches) {
            // Padding garante desempate por ID numérico, mesmo na ordenação lexicográfica do Redis.
            args.add(String.format(Locale.ROOT, "%010d", batch.batchId()));
            args.add(Long.toString(batch.expirationDate().toEpochDay()));
        }
        var result = redis.execute(replaceScript,
                List.of(key(kitchenId), key(kitchenId) + ":build:" + UUID.randomUUID()), args.toArray());
        if (!Long.valueOf(1).equals(result)) {
            throw new org.springframework.data.redis.RedisSystemException("Ranking não publicado.", null);
        }
    }

    public Optional<List<RankingEntry>> top(Integer kitchenId, int limit) {
        var tuples = redis.opsForZSet().rangeWithScores(key(kitchenId), 0, limit - 1);
        if (tuples == null || tuples.isEmpty()) return Optional.empty();
        List<RankingEntry> entries = new ArrayList<>();
        for (var tuple : tuples) {
            if (EMPTY_MARKER.equals(tuple.getValue())) continue;
            long score = tuple.getScore().longValue();
            entries.add(new RankingEntry(entries.size() + 1, Integer.valueOf(tuple.getValue()),
                    LocalDate.ofEpochDay(score), score));
        }
        return Optional.of(List.copyOf(entries));
    }

    private String key(Integer kitchenId) {
        return prefix + ":{" + kitchenId + "}";
    }
}
