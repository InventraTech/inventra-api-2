package com.inventra.api.infrastructure.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.inventra.api.core.service.prelist.PreListUseCase;
import com.inventra.api.core.service.prelist.model.response.PreListResponse;
import com.inventra.api.infrastructure.security.Roles;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@Tag(name = "Pré-listas", description = "Pré-listas de compra importadas do banco do 1º ano (somente leitura)")
@RequestMapping("/api/pre-lists")
@RequiredArgsConstructor
public class PreListController {

    private final PreListUseCase useCase;

    @Operation(summary = "Lista as pré-listas, da mais recente para a mais antiga",
            description = "Só o cabeçalho (data e origem); os itens vêm em GET /api/pre-lists/{id}.")
    @PreAuthorize(Roles.REQUISITION)
    @GetMapping
    public ResponseEntity<List<PreListResponse>> list() {
        List<PreListResponse> responses = useCase.list().stream()
                .map(PreListResponse::summary)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @Operation(summary = "Busca uma pré-lista pelo id, com os itens")
    @PreAuthorize(Roles.REQUISITION)
    @GetMapping("/{id}")
    public ResponseEntity<PreListResponse> findById(@PathVariable Integer id) {
        return ResponseEntity.ok(PreListResponse.fromEntity(useCase.findById(id), useCase.listItems(id)));
    }
}
