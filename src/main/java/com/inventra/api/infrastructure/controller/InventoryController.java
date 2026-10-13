package com.inventra.api.infrastructure.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.inventra.api.core.domain.inventory.Inventory;
import com.inventra.api.core.domain.inventory.InventoryCount;
import com.inventra.api.core.service.inventory.InventoryUseCase;
import com.inventra.api.core.service.inventory.model.request.OpenInventoryRequest;
import com.inventra.api.core.service.inventory.model.request.RegisterInventoryCountRequest;
import com.inventra.api.core.service.inventory.model.response.InventoryCountResponse;
import com.inventra.api.core.service.inventory.model.response.InventoryResponse;
import com.inventra.api.infrastructure.security.Roles;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@PreAuthorize(Roles.STOCK)
@RestController
@Tag(name = "Inventários", description = "Contagem física de estoque por cozinha")
@RequestMapping("/api/inventories")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryUseCase useCase;

    @Operation(summary = "Abre um inventário para uma cozinha")
    @PostMapping
    public ResponseEntity<InventoryResponse> open(@Valid @RequestBody OpenInventoryRequest request) {
        Inventory opened = useCase.open(request);
        InventoryResponse response = InventoryResponse.fromEntity(opened);
        return ResponseEntity.created(URI.create("/api/inventories/" + response.id())).body(response);
    }

    @Operation(summary = "Busca um inventário pelo id")
    @GetMapping("/{id}")
    public ResponseEntity<InventoryResponse> findById(@PathVariable Integer id) {
        return ResponseEntity.ok(InventoryResponse.fromEntity(useCase.findById(id)));
    }

    @Operation(summary = "Lista os inventários de uma cozinha")
    @GetMapping
    public ResponseEntity<List<InventoryResponse>> listByKitchen(@RequestParam Integer kitchenId) {
        List<InventoryResponse> responses = useCase.listByKitchen(kitchenId).stream()
                .map(InventoryResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @Operation(summary = "Fecha o inventário (procedure sp_close_inventory) e calcula as divergências")
    @PatchMapping("/{id}/close")
    public ResponseEntity<InventoryResponse> close(@PathVariable Integer id) {
        return ResponseEntity.ok(InventoryResponse.fromEntity(useCase.close(id)));
    }

    @Operation(summary = "Cancela um inventário aberto")
    @PatchMapping("/{id}/cancel")
    public ResponseEntity<InventoryResponse> cancel(@PathVariable Integer id) {
        return ResponseEntity.ok(InventoryResponse.fromEntity(useCase.cancel(id)));
    }

    @Operation(summary = "Registra a contagem de um lote no inventário")
    @PostMapping("/{id}/counts")
    public ResponseEntity<InventoryCountResponse> registerCount(@PathVariable Integer id,
                                                                  @Valid @RequestBody RegisterInventoryCountRequest request) {
        InventoryCount count = useCase.registerCount(id, request);
        InventoryCountResponse response = InventoryCountResponse.fromEntity(count);
        return ResponseEntity.created(URI.create("/api/inventories/" + id + "/counts/" + response.id())).body(response);
    }

    @Operation(summary = "Lista as contagens de um inventário")
    @GetMapping("/{id}/counts")
    public ResponseEntity<List<InventoryCountResponse>> listCounts(@PathVariable Integer id) {
        List<InventoryCountResponse> responses = useCase.listCounts(id).stream()
                .map(InventoryCountResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @Operation(summary = "Remove uma contagem do inventário")
    @DeleteMapping("/{id}/counts/{countId}")
    public ResponseEntity<InventoryResponse> removeCount(@PathVariable Integer id, @PathVariable Integer countId) {
        return ResponseEntity.ok(InventoryResponse.fromEntity(useCase.removeCount(id, countId)));
    }
}
