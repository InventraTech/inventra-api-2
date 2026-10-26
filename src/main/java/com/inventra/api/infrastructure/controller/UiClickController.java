package com.inventra.api.infrastructure.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.inventra.api.core.service.uiclick.UiClickCounterUseCase;
import com.inventra.api.core.service.uiclick.UiClickPlatform;
import com.inventra.api.core.service.uiclick.UiClickRanking;
import com.inventra.api.core.service.uiclick.model.request.RecordUiClicksRequest;
import com.inventra.api.core.service.uiclick.model.response.UiClickBatchResponse;
import com.inventra.api.infrastructure.security.Roles;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@Tag(name = "Eventos de clique", description = "Contador de cliques por elemento do web e do mobile (tb_ui_click_counter)")
@RequestMapping("/api/events/clicks")
@RequiredArgsConstructor
public class UiClickController {

    private final UiClickCounterUseCase useCase;

    // qualquer usuário autenticado: o app guarda os cliques de antes do login e envia depois
    @Operation(summary = "Soma um lote de cliques no contador",
            description = "Chave = nome da coluna em tb_ui_click_counter (tipo_plataforma_tela_nome), valor = "
                    + "quantidade de cliques. Elemento desconhecido não derruba o lote: é ignorado e volta em ignored.")
    @PostMapping
    public ResponseEntity<UiClickBatchResponse> record(@Valid @RequestBody RecordUiClicksRequest body) {
        return ResponseEntity.ok(UiClickBatchResponse.from(useCase.record(body.clicks())));
    }

    @Operation(summary = "Ranking de cliques por elemento (vw_ui_click_ranking)",
            description = "Filtro opcional: platform (WEB ou MOBILE). Elemento sem cliques aparece com 0.")
    @PreAuthorize(Roles.SUPERVISOR)
    @GetMapping("/ranking")
    public ResponseEntity<List<UiClickRanking>> ranking(@RequestParam(required = false) UiClickPlatform platform) {
        return ResponseEntity.ok(useCase.ranking(platform));
    }
}
