package com.inventra.api.core.service.alert.model.response;

import java.time.LocalDateTime;

import com.inventra.api.core.domain.alert.Alert;
import com.inventra.api.core.domain.alert.enums.AlertSeverity;
import com.inventra.api.core.service.alert.AlertView;

public record AlertResponse(
        Integer id,
        String type,
        AlertSeverity severity,
        BatchSummary batch,
        ProductSummary product,
        KitchenSummary kitchen,
        String message,
        // lido por quem está chamando
        Boolean read,
        // o que originou o alerta (id da requisição, id do pedido de entrada ou código de barras): o app usa
        // para saber aonde levar a pessoa quando ela toca na notificação
        String refKey,
        LocalDateTime createdAt
) {
    public record BatchSummary(Integer id, String batchNumber) {
    }

    public record ProductSummary(Integer id, String name) {
    }

    public record KitchenSummary(Integer id, String name) {
    }

    public static AlertResponse from(AlertView view) {
        return fromEntity(view.alert(), view.read());
    }

    public static AlertResponse fromEntity(Alert alert) {
        return fromEntity(alert, Boolean.TRUE.equals(alert.getRead()));
    }

    private static AlertResponse fromEntity(Alert alert, boolean read) {
        var batch = alert.getBatch() != null
                ? new BatchSummary(alert.getBatch().getId(), alert.getBatch().getBatchNumber())
                : null;
        var product = alert.getProduct() != null
                ? new ProductSummary(alert.getProduct().getId(), alert.getProduct().getName())
                : null;

        return new AlertResponse(
                alert.getId(),
                alert.getType(),
                alert.getSeverity(),
                batch,
                product,
                new KitchenSummary(alert.getKitchen().getId(), alert.getKitchen().getName()),
                alert.getMessage(),
                read,
                alert.getRefKey(),
                alert.getCreatedAt()
        );
    }
}
