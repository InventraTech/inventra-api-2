package com.inventra.api.core.service.stockmovement.model.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.inventra.api.core.domain.stock.StockMovement;
import com.inventra.api.core.domain.stock.enums.StockMovementReason;
import com.inventra.api.core.domain.stock.enums.StockMovementType;

public record StockMovementResponse(
        Integer id,
        StockMovementType type,
        // só nas baixas (WRITE_OFF)
        StockMovementReason reason,
        // entrada e baixa: quantidade movimentada; ajuste: diferença, com sinal
        BigDecimal quantity,
        // saldo do lote logo depois da movimentação
        BigDecimal balanceAfter,
        BatchSummary batch,
        ProductSummary product,
        // nulo quando a movimentação não veio de uma pessoa
        UserSummary user,
        Integer requisitionId,
        LocalDateTime createdAt
) {
    public record BatchSummary(Integer id, String batchNumber) {
    }

    public record ProductSummary(Integer id, String name) {
    }

    public record UserSummary(UUID id, String name) {
    }

    public static StockMovementResponse fromEntity(StockMovement movement) {
        var batch = movement.getBatch();
        var user = movement.getUser() != null
                ? new UserSummary(movement.getUser().getId(), movement.getUser().getName())
                : null;

        return new StockMovementResponse(
                movement.getId(),
                movement.getType(),
                movement.getReason(),
                movement.getQuantity(),
                movement.getBalanceAfter(),
                new BatchSummary(batch.getId(), batch.getBatchNumber()),
                new ProductSummary(batch.getProduct().getId(), batch.getProduct().getName()),
                user,
                movement.getRequisition() != null ? movement.getRequisition().getId() : null,
                movement.getCreatedAt()
        );
    }
}
