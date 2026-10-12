package com.inventra.api.core.service.requisition.model.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.inventra.api.core.domain.requisition.Requisition;
import com.inventra.api.core.domain.requisition.enums.RequisitionStatus;
import com.inventra.api.core.domain.requisition.enums.RequisitionType;
import com.inventra.api.core.service.requisition.RequisitionItemsSummary;

public record RequisitionResponse(
        Integer id,
        RequisitionType type,
        String origin,
        RequisitionStatus status,
        String reason,
        KitchenSummary kitchen,
        UserSummary requester,
        UserSummary approver,
        LocalDateTime createdAt,
        LocalDateTime approvedAt,
        // resumo dos itens, para o card da listagem
        int itemCount,
        BigDecimal estimatedTotal,
        List<String> itemNames
) {
    public record KitchenSummary(Integer id, String name) {
    }

    public record UserSummary(UUID id, String name) {
    }

    public static RequisitionResponse fromEntity(Requisition requisition) {
        return fromEntity(requisition, RequisitionItemsSummary.EMPTY);
    }

    public static RequisitionResponse fromEntity(Requisition requisition, RequisitionItemsSummary items) {
        var approver = requisition.getApprover() != null
                ? new UserSummary(requisition.getApprover().getId(), requisition.getApprover().getName())
                : null;

        return new RequisitionResponse(
                requisition.getId(),
                requisition.getType(),
                requisition.getOrigin(),
                requisition.getStatus(),
                requisition.getReason(),
                new KitchenSummary(requisition.getKitchen().getId(), requisition.getKitchen().getName()),
                new UserSummary(requisition.getRequester().getId(), requisition.getRequester().getName()),
                approver,
                requisition.getCreatedAt(),
                requisition.getApprovedAt(),
                items.itemCount(),
                items.estimatedTotal(),
                items.itemNames()
        );
    }
}
