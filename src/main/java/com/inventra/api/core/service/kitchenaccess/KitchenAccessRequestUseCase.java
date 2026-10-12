package com.inventra.api.core.service.kitchenaccess;

import java.util.List;

public interface KitchenAccessRequestUseCase {

    // Usuário sem cozinha pede entrada pelo código de convite ou pelo id da cozinha escolhida na descoberta
    // (exatamente um dos dois); fica pendente até um supervisor decidir.
    KitchenAccessRequest request(String code, Integer kitchenId);

    // Pedido mais recente do próprio usuário.
    KitchenAccessRequest findMine();

    // Supervisor: pedidos da própria cozinha (status nulo = todos, inclusive o histórico).
    List<KitchenAccessRequest> listForMyKitchen(KitchenAccessRequestStatus status);

    // Supervisor: aprova e vincula o usuário à cozinha.
    KitchenAccessRequest approve(String id);

    // Supervisor: recusa, com motivo opcional.
    KitchenAccessRequest reject(String id, String reason);
}
