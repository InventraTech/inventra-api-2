package com.inventra.api.core.service.kitchen.model.response;

import com.inventra.api.core.service.kitchenaccess.KitchenAccessRequestStatus;

// Item da descoberta de cozinhas (lista, mapa e bottom sheet). De propósito não traz código, endereço
// completo, membros nem estoque: nada que permita entrar sem a aprovação do supervisor.
public record KitchenDiscoveryResponse(
        Integer id,
        String name,
        String city,
        String state,
        // nulos = cozinha sem pin no mapa
        Double latitude,
        Double longitude,
        // só quando a busca informa lat/lng
        Double distanceKm,
        // pedido do usuário logado para esta cozinha: PENDING, REJECTED ou null
        KitchenAccessRequestStatus myRequestStatus
) {
}
