package com.inventra.api.core.service.kitchenaccess.model.response;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.inventra.api.core.service.kitchenaccess.KitchenAccessRequest;
import com.inventra.api.core.service.kitchenaccess.KitchenAccessRequestStatus;

public record KitchenAccessRequestResponse(
        String id,
        KitchenAccessRequestStatus status,
        KitchenSummary kitchen,
        UserSummary user,
        Instant createdAt,
        Instant expiresAt,
        Instant decidedAt,
        UUID decidedBy,
        String reason
) {

    public record KitchenSummary(Integer id, String name) {
    }

    public record UserSummary(UUID id, String name, String email) {
    }

    // expiresAt só faz sentido enquanto o pedido está pendente: depois de decidido ele não expira mais.
    public static KitchenAccessRequestResponse from(KitchenAccessRequest request, Duration pendingTtl) {
        return new KitchenAccessRequestResponse(
                request.id(),
                request.status(),
                new KitchenSummary(request.kitchenId(), request.kitchenName()),
                new UserSummary(request.userId(), request.userName(), request.userEmail()),
                request.createdAt(),
                request.status() == KitchenAccessRequestStatus.PENDING ? request.createdAt().plus(pendingTtl) : null,
                request.decidedAt(),
                request.decidedBy(),
                request.reason());
    }
}
