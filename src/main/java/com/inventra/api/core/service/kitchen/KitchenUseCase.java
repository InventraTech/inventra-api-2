package com.inventra.api.core.service.kitchen;

import java.util.List;

import com.inventra.api.core.service.kitchen.model.request.CreateKitchenRequest;
import com.inventra.api.core.service.kitchen.model.request.UpdateKitchenRequest;
import com.inventra.api.core.service.kitchen.model.response.KitchenDiscoveryPage;
import com.inventra.api.core.domain.kitchen.Kitchen;

public interface KitchenUseCase {

    Kitchen create(CreateKitchenRequest request);

    Kitchen findById(Integer id);

    Kitchen findByCode(String code);

    List<Kitchen> listActive();

    // Cozinhas ativas que aceitam pedidos de entrada, para qualquer usuário autenticado. Com lat/lng a
    // lista vem por distância, limitada a radiusKm (padrão 50).
    KitchenDiscoveryPage discover(String name, Double lat, Double lng, Double radiusKm, int page, int size);

    Kitchen update(Integer id, UpdateKitchenRequest request);

    void activate(Integer id);

    void deactivate(Integer id);
}
