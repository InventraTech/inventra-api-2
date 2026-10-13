package com.inventra.api.core.service.kitchenaccess;

import java.time.Instant;
import java.util.UUID;

// Pedido de entrada de um usuário numa cozinha. Fica no Redis: pendente expira sozinho; depois de decidido
// (aprovado ou recusado) o registro permanece como histórico de quem decidiu e quando.
public record KitchenAccessRequest(
        String id,
        UUID userId,
        String userName,
        String userEmail,
        Integer kitchenId,
        String kitchenName,
        KitchenAccessRequestStatus status,
        Instant createdAt,
        Instant decidedAt,
        UUID decidedBy,
        String reason
) {

    public boolean isPending() {
        return status == KitchenAccessRequestStatus.PENDING;
    }
}
