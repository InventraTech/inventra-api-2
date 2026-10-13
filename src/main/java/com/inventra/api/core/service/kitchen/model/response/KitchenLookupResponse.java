package com.inventra.api.core.service.kitchen.model.response;

import com.inventra.api.core.domain.kitchen.Kitchen;

// Só o necessário para o usuário confirmar a cozinha antes de pedir entrada: sem endereço nem código.
public record KitchenLookupResponse(
        Integer id,
        String name
) {
    public static KitchenLookupResponse fromEntity(Kitchen kitchen) {
        return new KitchenLookupResponse(kitchen.getId(), kitchen.getName());
    }
}
