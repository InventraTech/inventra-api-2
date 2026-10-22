package com.inventra.api.infrastructure.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.inventra.api.core.service.ranking.RankingEntry;
import com.inventra.api.core.service.ranking.RankingService;
import com.inventra.api.infrastructure.security.Roles;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@PreAuthorize(Roles.STOCK)
@RestController
@Tag(name = "Lotes de estoque", description = "Entradas, baixas e consultas de lotes")
@RequestMapping("/api/stock-batches/ranking")
@RequiredArgsConstructor
public class RankingController {
    private final RankingService service;

    @Operation(
            summary = "Consulta o ranking de lotes por validade",
            description = "Retorna os lotes da cozinha do vencimento mais próximo para o mais distante.")
    @ApiResponse(responseCode = "200", description = "Ranking retornado com sucesso")
    @ApiResponse(responseCode = "503", description = "Redis temporariamente indisponível")
    @GetMapping
    public List<RankingEntry> top(@RequestParam Integer kitchenId,
            @RequestParam(defaultValue = "10") int limit) {
        return service.top(kitchenId, limit);
    }
}
