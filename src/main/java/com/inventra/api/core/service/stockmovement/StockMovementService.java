package com.inventra.api.core.service.stockmovement;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.inventra.api.core.domain.stock.StockMovement;
import com.inventra.api.core.domain.stock.enums.StockMovementType;
import com.inventra.api.infrastructure.repository.StockMovementRepository;
import com.inventra.api.infrastructure.security.KitchenAccessGuard;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StockMovementService implements StockMovementUseCase {

    static final int MAX_LIMIT = 500;

    private final StockMovementRepository repository;
    private final KitchenAccessGuard accessGuard;

    @Override
    public List<StockMovement> list(Integer kitchenId, Integer productId, StockMovementType type, int limit) {
        accessGuard.assertAccess(kitchenId);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("O parâmetro limit deve ficar entre 1 e " + MAX_LIMIT + ".");
        }
        return repository.search(kitchenId, productId, type, PageRequest.of(0, limit));
    }
}
