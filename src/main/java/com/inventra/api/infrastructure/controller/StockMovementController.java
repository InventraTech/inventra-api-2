package com.inventra.api.infrastructure.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.inventra.api.core.domain.stock.enums.StockMovementType;
import com.inventra.api.core.service.stockmovement.StockMovementUseCase;
import com.inventra.api.core.service.stockmovement.model.response.StockMovementResponse;
import com.inventra.api.infrastructure.security.Roles;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@PreAuthorize(Roles.STOCK)
@RestController
@Tag(name = "Movimentações de estoque", description = "Histórico de entradas, baixas e ajustes dos lotes")
@RequestMapping("/api/stock-movements")
@RequiredArgsConstructor
public class StockMovementController {

    private final StockMovementUseCase useCase;

    @Operation(summary = "Lista as movimentações de uma cozinha, da mais recente para a mais antiga",
            description = "Filtros opcionais: productId e type (ENTRY, WRITE_OFF ou ADJUSTMENT). "
                    + "limit padrão 200, máximo 500.")
    @GetMapping
    public ResponseEntity<List<StockMovementResponse>> list(@RequestParam Integer kitchenId,
                                                             @RequestParam(required = false) Integer productId,
                                                             @RequestParam(required = false) StockMovementType type,
                                                             @RequestParam(defaultValue = "200") int limit) {
        List<StockMovementResponse> responses = useCase.list(kitchenId, productId, type, limit).stream()
                .map(StockMovementResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(responses);
    }
}
