package com.inventra.api.core.service.stockmovement;

import java.util.List;

import com.inventra.api.core.domain.stock.StockMovement;
import com.inventra.api.core.domain.stock.enums.StockMovementType;

public interface StockMovementUseCase {

    // Entradas, baixas e ajustes da cozinha, do mais recente para o mais antigo (no máximo "limit").
    List<StockMovement> list(Integer kitchenId, Integer productId, StockMovementType type, int limit);
}
