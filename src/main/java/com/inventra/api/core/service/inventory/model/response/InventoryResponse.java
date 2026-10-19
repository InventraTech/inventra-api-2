package com.inventra.api.core.service.inventory.model.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.inventra.api.core.domain.inventory.Inventory;
import com.inventra.api.core.domain.inventory.enums.InventoryStatus;

public record InventoryResponse(
        Integer id,
        KitchenSummary kitchen,
        UserSummary responsible,
        LocalDateTime startedAt,
        LocalDateTime closedAt,
        InventoryStatus status,
        String note
) {
    public record KitchenSummary(Integer id, String name) {
    }

    public record UserSummary(UUID id, String name, String photoUrl) {
    }

    public static InventoryResponse fromEntity(Inventory inventory) {
        return new InventoryResponse(
                inventory.getId(),
                new KitchenSummary(inventory.getKitchen().getId(), inventory.getKitchen().getName()),
                new UserSummary(inventory.getResponsible().getId(), inventory.getResponsible().getName(),
                        inventory.getResponsible().getPhotoUrl()),
                inventory.getStartedAt(),
                inventory.getClosedAt(),
                inventory.getStatus(),
                inventory.getNote()
        );
    }
}
