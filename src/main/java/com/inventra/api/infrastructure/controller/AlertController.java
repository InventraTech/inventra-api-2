package com.inventra.api.infrastructure.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.inventra.api.core.domain.alert.Alert;
import com.inventra.api.core.service.alert.AlertUseCase;
import com.inventra.api.core.service.alert.AlertView;
import com.inventra.api.core.service.alert.model.request.CreateAlertRequest;
import com.inventra.api.core.service.alert.model.request.ReportUnregisteredProductRequest;
import com.inventra.api.core.service.alert.model.response.AlertResponse;
import com.inventra.api.infrastructure.security.Roles;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// Consultar e marcar como lido é de qualquer usuário da cozinha: cada um só enxerga os alertas que são
// para ele (o comprador, por exemplo, só os das próprias requisições).
@RestController
@Tag(name = "Alertas", description = "Notificações da cozinha: estoque, validade, pedidos de entrada e requisições")
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertUseCase useCase;

    @Operation(summary = "Cria um alerta manualmente")
    @PreAuthorize(Roles.SUPERVISOR)
    @PostMapping
    public ResponseEntity<AlertResponse> create(@Valid @RequestBody CreateAlertRequest request) {
        Alert created = useCase.create(request);
        AlertResponse response = AlertResponse.fromEntity(created);
        return ResponseEntity.created(URI.create("/api/alerts/" + response.id())).body(response);
    }

    @Operation(summary = "Avisa o supervisor que um produto escaneado não está no catálogo",
            description = "Cria um alerta UNREGISTERED_PRODUCT para os supervisores da cozinha. Se já existe um "
                    + "aviso aberto para o mesmo código, devolve o existente (200) em vez de criar outro (201).")
    @PreAuthorize(Roles.STOCK)
    @PostMapping("/unregistered-product")
    public ResponseEntity<AlertResponse> reportUnregisteredProduct(
            @Valid @RequestBody ReportUnregisteredProductRequest request) {
        var report = useCase.reportUnregisteredProduct(request.kitchenId(), request.barcode());
        AlertResponse response = AlertResponse.fromEntity(report.alert());
        return report.created()
                ? ResponseEntity.created(URI.create("/api/alerts/" + response.id())).body(response)
                : ResponseEntity.ok(response);
    }

    @Operation(summary = "Busca um alerta pelo id")
    @GetMapping("/{id}")
    public ResponseEntity<AlertResponse> findById(@PathVariable Integer id) {
        return ResponseEntity.ok(AlertResponse.from(useCase.findById(id)));
    }

    @Operation(summary = "Lista os alertas do usuário logado em uma cozinha (opcionalmente só os não lidos)")
    @GetMapping
    public ResponseEntity<List<AlertResponse>> listByKitchen(
            @RequestParam Integer kitchenId,
            @RequestParam(required = false, defaultValue = "false") boolean unread) {
        List<AlertView> alerts = unread ? useCase.listUnreadByKitchen(kitchenId) : useCase.listByKitchen(kitchenId);
        List<AlertResponse> responses = alerts.stream()
                .map(AlertResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @Operation(summary = "Marca um alerta como lido para o usuário logado")
    @PatchMapping("/{id}/read")
    public ResponseEntity<AlertResponse> markAsRead(@PathVariable Integer id) {
        return ResponseEntity.ok(AlertResponse.from(useCase.markAsRead(id)));
    }

    @Operation(summary = "Marca como lidos todos os alertas do usuário logado na cozinha")
    @PatchMapping("/read-all")
    public ResponseEntity<Void> markAllAsRead(@RequestParam Integer kitchenId) {
        useCase.markAllAsRead(kitchenId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Exclui um alerta")
    @PreAuthorize(Roles.SUPERVISOR)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Integer id) {
        useCase.delete(id);
        return ResponseEntity.noContent().build();
    }
}
