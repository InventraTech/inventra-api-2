package com.inventra.api.infrastructure.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.inventra.api.core.service.kitchenaccess.KitchenAccessRequest;
import com.inventra.api.core.service.kitchenaccess.KitchenAccessRequestStatus;
import com.inventra.api.core.service.kitchenaccess.KitchenAccessRequestStore;

import lombok.RequiredArgsConstructor;

// Chaves:
//   kar:{id}               hash do pedido; com TTL enquanto PENDING, sem TTL depois de decidido
//   kar:user:{userId}      id do pedido mais recente do usuário
//   kar:user:{userId}:kitchens   hash kitchenId -> id do pedido mais recente do usuário naquela cozinha
//   kar:kitchen:{id}       sorted set (score = criação) com os pedidos da cozinha
// Quando um pedido pendente expira, o id fica no sorted set da cozinha; a limpeza é feita na leitura.
@Component
@Profile("!test")
@RequiredArgsConstructor
public class RedisKitchenAccessRequestStore implements KitchenAccessRequestStore {

    private static final String REQUEST_PREFIX = "kar:";
    private static final String USER_PREFIX = "kar:user:";
    private static final String KITCHEN_PREFIX = "kar:kitchen:";
    private static final String USER_KITCHENS_SUFFIX = ":kitchens";

    // Compare-and-set atômico: só decide se ainda estiver PENDING e tira o TTL na mesma operação.
    private static final DefaultRedisScript<Long> DECIDE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'status') == 'PENDING' then
              redis.call('HSET', KEYS[1], 'status', ARGV[1], 'decidedAt', ARGV[2], 'decidedBy', ARGV[3], 'reason', ARGV[4])
              redis.call('PERSIST', KEYS[1])
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    @Override
    public void savePending(KitchenAccessRequest request, Duration ttl) {
        String key = REQUEST_PREFIX + request.id();
        Map<String, String> fields = new HashMap<>();
        fields.put("userId", request.userId().toString());
        fields.put("userName", request.userName());
        fields.put("userEmail", request.userEmail());
        fields.put("kitchenId", String.valueOf(request.kitchenId()));
        fields.put("kitchenName", request.kitchenName());
        fields.put("status", request.status().name());
        fields.put("createdAt", String.valueOf(request.createdAt().toEpochMilli()));
        redis.opsForHash().putAll(key, fields);
        redis.expire(key, ttl);

        redis.opsForValue().set(USER_PREFIX + request.userId(), request.id());
        redis.opsForHash().put(USER_PREFIX + request.userId() + USER_KITCHENS_SUFFIX,
                String.valueOf(request.kitchenId()), request.id());
        redis.opsForZSet().add(KITCHEN_PREFIX + request.kitchenId(), request.id(), request.createdAt().toEpochMilli());
    }

    @Override
    public Optional<KitchenAccessRequest> find(String id) {
        Map<Object, Object> fields = redis.opsForHash().entries(REQUEST_PREFIX + id);
        if (fields.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new KitchenAccessRequest(
                id,
                UUID.fromString((String) fields.get("userId")),
                (String) fields.get("userName"),
                (String) fields.get("userEmail"),
                Integer.valueOf((String) fields.get("kitchenId")),
                (String) fields.get("kitchenName"),
                KitchenAccessRequestStatus.valueOf((String) fields.get("status")),
                Instant.ofEpochMilli(Long.parseLong((String) fields.get("createdAt"))),
                fields.get("decidedAt") == null ? null : Instant.ofEpochMilli(Long.parseLong((String) fields.get("decidedAt"))),
                blankToNull((String) fields.get("decidedBy")) == null ? null : UUID.fromString((String) fields.get("decidedBy")),
                blankToNull((String) fields.get("reason"))));
    }

    @Override
    public Optional<KitchenAccessRequest> findLatestByUser(UUID userId) {
        String id = redis.opsForValue().get(USER_PREFIX + userId);
        return id == null ? Optional.empty() : find(id);
    }

    @Override
    public Map<Integer, KitchenAccessRequest> findLatestByUserPerKitchen(UUID userId) {
        String key = USER_PREFIX + userId + USER_KITCHENS_SUFFIX;
        Map<Integer, KitchenAccessRequest> latest = new HashMap<>();
        redis.opsForHash().entries(key).forEach((kitchenId, requestId) -> {
            Optional<KitchenAccessRequest> request = find((String) requestId);
            if (request.isPresent()) {
                latest.put(Integer.valueOf((String) kitchenId), request.get());
            } else {
                redis.opsForHash().delete(key, kitchenId);
            }
        });
        return latest;
    }

    @Override
    public List<KitchenAccessRequest> listByKitchen(Integer kitchenId) {
        String key = KITCHEN_PREFIX + kitchenId;
        Set<String> ids = redis.opsForZSet().reverseRange(key, 0, -1);
        List<KitchenAccessRequest> requests = new ArrayList<>();
        if (ids == null) {
            return requests;
        }
        for (String id : ids) {
            Optional<KitchenAccessRequest> request = find(id);
            if (request.isPresent()) {
                requests.add(request.get());
            } else {
                redis.opsForZSet().remove(key, id);
            }
        }
        return requests;
    }

    @Override
    public boolean decide(String id, KitchenAccessRequestStatus status, UUID decidedBy, String reason, Instant at) {
        Long result = redis.execute(DECIDE_SCRIPT, List.of(REQUEST_PREFIX + id),
                status.name(), String.valueOf(at.toEpochMilli()), decidedBy.toString(), reason == null ? "" : reason);
        return Long.valueOf(1L).equals(result);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
