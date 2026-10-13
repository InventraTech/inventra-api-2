package com.inventra.api.core.service.kitchen.model.response;

import java.time.LocalDateTime;

import com.inventra.api.core.domain.kitchen.Kitchen;

public record KitchenResponse(
        Integer id,
        String name,
        String code,
        String address,
        String city,
        String state,
        Double latitude,
        Double longitude,
        Boolean acceptingRequests,
        Boolean active,
        LocalDateTime createdAt
) {
    public static KitchenResponse fromEntity(Kitchen kitchen) {
        return new KitchenResponse(
                kitchen.getId(),
                kitchen.getName(),
                kitchen.getCode(),
                kitchen.getAddress(),
                kitchen.getCity(),
                kitchen.getState(),
                kitchen.getLatitude(),
                kitchen.getLongitude(),
                kitchen.getAcceptingRequests(),
                kitchen.getActive(),
                kitchen.getCreatedAt()
        );
    }
}
