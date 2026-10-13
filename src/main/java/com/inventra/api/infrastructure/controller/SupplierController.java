package com.inventra.api.infrastructure.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.inventra.api.core.domain.supplier.Supplier;
import com.inventra.api.core.service.supplier.SupplierUseCase;
import com.inventra.api.core.service.supplier.model.request.CreateSupplierRequest;
import com.inventra.api.core.service.supplier.model.request.UpdateSupplierRequest;
import com.inventra.api.core.service.supplier.model.response.SupplierResponse;
import com.inventra.api.infrastructure.security.Roles;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@Tag(name = "Fornecedores", description = "Fornecedores de produtos")
@RequestMapping("/api/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierUseCase useCase;

    @Operation(summary = "Cria um fornecedor")
    @PreAuthorize(Roles.SUPERVISOR)
    @PostMapping
    public ResponseEntity<SupplierResponse> create(@Valid @RequestBody CreateSupplierRequest request) {
        Supplier created = useCase.create(request);
        SupplierResponse response = SupplierResponse.fromEntity(created);
        return ResponseEntity.created(URI.create("/api/suppliers/" + response.id())).body(response);
    }

    @Operation(summary = "Busca um fornecedor pelo id")
    @GetMapping("/{id}")
    public ResponseEntity<SupplierResponse> findById(@PathVariable Integer id) {
        return ResponseEntity.ok(SupplierResponse.fromEntity(useCase.findById(id)));
    }

    @Operation(summary = "Lista os fornecedores ativos",
            description = "Com kitchenId, só os que já entregaram algum lote na cozinha (a do próprio usuário).")
    @GetMapping
    public ResponseEntity<List<SupplierResponse>> listActive(@RequestParam(required = false) Integer kitchenId) {
        List<Supplier> suppliers = kitchenId != null ? useCase.listActiveByKitchen(kitchenId) : useCase.listActive();
        List<SupplierResponse> responses = suppliers.stream()
                .map(SupplierResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @Operation(summary = "Atualiza um fornecedor")
    @PreAuthorize(Roles.SUPERVISOR)
    @PutMapping("/{id}")
    public ResponseEntity<SupplierResponse> update(@PathVariable Integer id,
                                                    @Valid @RequestBody UpdateSupplierRequest request) {
        Supplier updated = useCase.update(id, request);
        return ResponseEntity.ok(SupplierResponse.fromEntity(updated));
    }

    @Operation(summary = "Ativa um fornecedor")
    @PreAuthorize(Roles.SUPERVISOR)
    @PatchMapping("/{id}/activate")
    public ResponseEntity<Void> activate(@PathVariable Integer id) {
        useCase.activate(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Desativa um fornecedor")
    @PreAuthorize(Roles.SUPERVISOR)
    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivate(@PathVariable Integer id) {
        useCase.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
