package com.inventra.api.core.service.kitchenaccess;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// Porta de armazenamento dos pedidos de entrada. Em produção é o Redis (RedisKitchenAccessRequestStore);
// os testes usam uma implementação em memória.
public interface KitchenAccessRequestStore {

    // Grava um pedido novo (pendente) que expira depois de ttl, e o registra como o mais recente do usuário.
    void savePending(KitchenAccessRequest request, Duration ttl);

    // Pedido que já expirou não existe mais.
    Optional<KitchenAccessRequest> find(String id);

    Optional<KitchenAccessRequest> findLatestByUser(UUID userId);

    // Pedido mais recente do usuário em cada cozinha (kitchenId -> pedido): a descoberta de cozinhas mostra
    // "pendente" ou "recusado" por cozinha. Pedido pendente que expirou não aparece.
    Map<Integer, KitchenAccessRequest> findLatestByUserPerKitchen(UUID userId);

    // Pedidos da cozinha, do mais novo para o mais antigo; o histórico inclui os já decididos.
    List<KitchenAccessRequest> listByKitchen(Integer kitchenId);

    // Decide o pedido só se ainda estiver pendente (duas decisões simultâneas: só uma vence) e tira o
    // prazo de expiração, para o histórico não sumir. Devolve false se já não estava pendente.
    boolean decide(String id, KitchenAccessRequestStatus status, UUID decidedBy, String reason, Instant at);
}
