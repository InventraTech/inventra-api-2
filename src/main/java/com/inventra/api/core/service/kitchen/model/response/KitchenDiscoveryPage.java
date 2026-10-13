package com.inventra.api.core.service.kitchen.model.response;

import java.util.List;

public record KitchenDiscoveryPage(
        List<KitchenDiscoveryResponse> content,
        int page,
        int size,
        long totalElements
) {
}
