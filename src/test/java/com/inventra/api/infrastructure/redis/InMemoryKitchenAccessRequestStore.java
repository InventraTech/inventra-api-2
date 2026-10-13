package com.inventra.api.infrastructure.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.inventra.api.core.service.kitchenaccess.KitchenAccessRequest;
import com.inventra.api.core.service.kitchenaccess.KitchenAccessRequestStatus;
import com.inventra.api.core.service.kitchenaccess.KitchenAccessRequestStore;

// Substitui o Redis nos testes (perfil "test"): mesmo contrato, sem rede. Expiração não é simulada.
@Component
@Profile("test")
public class InMemoryKitchenAccessRequestStore implements KitchenAccessRequestStore {

    private final Map<String, KitchenAccessRequest> requests = new ConcurrentHashMap<>();
    private final Map<UUID, String> latestByUser = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Integer, String>> latestByUserAndKitchen = new ConcurrentHashMap<>();

    @Override
    public void savePending(KitchenAccessRequest request, Duration ttl) {
        requests.put(request.id(), request);
        latestByUser.put(request.userId(), request.id());
        latestByUserAndKitchen.computeIfAbsent(request.userId(), id -> new ConcurrentHashMap<>())
                .put(request.kitchenId(), request.id());
    }

    @Override
    public Map<Integer, KitchenAccessRequest> findLatestByUserPerKitchen(UUID userId) {
        Map<Integer, KitchenAccessRequest> latest = new HashMap<>();
        latestByUserAndKitchen.getOrDefault(userId, Map.of())
                .forEach((kitchenId, requestId) -> find(requestId).ifPresent(request -> latest.put(kitchenId, request)));
        return latest;
    }

    @Override
    public Optional<KitchenAccessRequest> find(String id) {
        return Optional.ofNullable(requests.get(id));
    }

    @Override
    public Optional<KitchenAccessRequest> findLatestByUser(UUID userId) {
        String id = latestByUser.get(userId);
        return id == null ? Optional.empty() : find(id);
    }

    @Override
    public List<KitchenAccessRequest> listByKitchen(Integer kitchenId) {
        return requests.values().stream()
                .filter(request -> request.kitchenId().equals(kitchenId))
                .sorted(Comparator.comparing(KitchenAccessRequest::createdAt).reversed())
                .toList();
    }

    @Override
    public synchronized boolean decide(String id, KitchenAccessRequestStatus status, UUID decidedBy, String reason,
                                       Instant at) {
        KitchenAccessRequest current = requests.get(id);
        if (current == null || !current.isPending()) {
            return false;
        }
        requests.put(id, new KitchenAccessRequest(current.id(), current.userId(), current.userName(),
                current.userEmail(), current.kitchenId(), current.kitchenName(), status, current.createdAt(), at,
                decidedBy, reason == null || reason.isBlank() ? null : reason));
        return true;
    }
}
