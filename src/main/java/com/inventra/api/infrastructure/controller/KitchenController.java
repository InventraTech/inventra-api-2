package com.inventra.api.infrastructure.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.service.kitchen.KitchenUseCase;
import com.inventra.api.core.service.kitchen.model.request.CreateKitchenRequest;
import com.inventra.api.core.service.kitchen.model.request.UpdateKitchenRequest;
import com.inventra.api.core.service.kitchen.model.response.KitchenLookupResponse;
import com.inventra.api.core.service.kitchen.model.response.KitchenResponse;
import com.inventra.api.core.service.stockbatch.StockBatchUseCase;
import com.inventra.api.core.service.stockbatch.model.response.KitchenStockItemResponse;
import com.inventra.api.infrastructure.security.Roles;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@Tag(name = "Cozinhas", description = "Cozinhas e unidades operacionais")
@RequestMapping("/api/kitchens")
@RequiredArgsConstructor
public class KitchenController {

    private final KitchenUseCase useCase;
    private final StockBatchUseCase stockBatchUseCase;

    @Operation(summary = "Cria uma cozinha")
    @PreAuthorize(Roles.SUPERVISOR)
    @PostMapping
    public ResponseEntity<KitchenResponse> create(@Valid @RequestBody CreateKitchenRequest request) {
        Kitchen created = useCase.create(request);
        KitchenResponse response = KitchenResponse.fromEntity(created);
        return ResponseEntity.created(URI.create("/api/kitchens/" + response.id())).body(response);
    }

    @Operation(summary = "Busca uma cozinha pelo id")
    @GetMapping("/{id}")
    public ResponseEntity<KitchenResponse> findById(@PathVariable Integer id) {
        return ResponseEntity.ok(KitchenResponse.fromEntity(useCase.findById(id)));
    }

    @Operation(summary = "Busca uma cozinha pelo código de convite (resposta resumida)")
    @GetMapping("/by-code/{code}")
    public ResponseEntity<KitchenLookupResponse> findByCode(@PathVariable String code) {
        return ResponseEntity.ok(KitchenLookupResponse.fromEntity(useCase.findByCode(code)));
    }

    @Operation(summary = "Lista as cozinhas ativas")
    @GetMapping
    public ResponseEntity<List<KitchenResponse>> listActive() {
        List<KitchenResponse> responses = useCase.listActive().stream()
                .map(KitchenResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @Operation(summary = "Resumo do estoque da cozinha",
            description = "Produtos ativos ligados à cozinha (com lote ou parâmetro de estoque), com o saldo dos "
                    + "lotes ativos e não vencidos, ordenados por nome. Filtros opcionais: categoryId e name.")
    @PreAuthorize(Roles.STOCK)
    @GetMapping("/{kitchenId}/stock")
    public ResponseEntity<List<KitchenStockItemResponse>> stock(@PathVariable Integer kitchenId,
                                                                  @RequestParam(required = false) Integer categoryId,
                                                                  @RequestParam(required = false) String name) {
        return ResponseEntity.ok(stockBatchUseCase.summarizeKitchenStock(kitchenId, categoryId, name));
    }

    @Operation(summary = "Atualiza uma cozinha")
    @PreAuthorize(Roles.SUPERVISOR)
    @PutMapping("/{id}")
    public ResponseEntity<KitchenResponse> update(@PathVariable Integer id,
                                                   @Valid @RequestBody UpdateKitchenRequest request) {
        Kitchen updated = useCase.update(id, request);
        return ResponseEntity.ok(KitchenResponse.fromEntity(updated));
    }

    @Operation(summary = "Ativa uma cozinha")
    @PreAuthorize(Roles.SUPERVISOR)
    @PatchMapping("/{id}/activate")
    public ResponseEntity<Void> activate(@PathVariable Integer id) {
        useCase.activate(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Desativa uma cozinha")
    @PreAuthorize(Roles.SUPERVISOR)
    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivate(@PathVariable Integer id) {
        useCase.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
