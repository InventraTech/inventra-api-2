package com.inventra.api.infrastructure.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.inventra.api.core.service.kitchenaccess.KitchenAccessRequest;
import com.inventra.api.core.service.kitchenaccess.KitchenAccessRequestService;
import com.inventra.api.core.service.kitchenaccess.KitchenAccessRequestStatus;
import com.inventra.api.core.service.kitchenaccess.model.request.CreateKitchenAccessRequest;
import com.inventra.api.core.service.kitchenaccess.model.request.RejectKitchenAccessRequest;
import com.inventra.api.core.service.kitchenaccess.model.response.KitchenAccessRequestResponse;
import com.inventra.api.infrastructure.security.Roles;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@Tag(name = "Acesso à cozinha", description = "Pedidos de entrada em uma cozinha, aprovados por um supervisor")
@RequestMapping("/api/kitchen-access-requests")
@RequiredArgsConstructor
public class KitchenAccessRequestController {

    private final KitchenAccessRequestService service;

    // qualquer usuário autenticado sem cozinha
    @Operation(summary = "Solicita entrada em uma cozinha, pelo id (descoberta) ou pelo código de convite")
    @PostMapping
    public ResponseEntity<KitchenAccessRequestResponse> request(@Valid @RequestBody CreateKitchenAccessRequest body) {
        KitchenAccessRequestResponse response = toResponse(service.request(body.code(), body.kitchenId()));
        return ResponseEntity.created(URI.create("/api/kitchen-access-requests/" + response.id())).body(response);
    }

    @Operation(summary = "Consulta o pedido de acesso do usuário logado")
    @GetMapping("/mine")
    public ResponseEntity<KitchenAccessRequestResponse> mine() {
        return ResponseEntity.ok(toResponse(service.findMine()));
    }

    @Operation(summary = "Lista os pedidos de acesso (opcionalmente por status)")
    @PreAuthorize(Roles.SUPERVISOR)
    @GetMapping
    public ResponseEntity<List<KitchenAccessRequestResponse>> list(
            @RequestParam(required = false) KitchenAccessRequestStatus status) {
        return ResponseEntity.ok(service.listForMyKitchen(status).stream().map(this::toResponse).toList());
    }

    @Operation(summary = "Aprova um pedido e vincula o usuário à cozinha")
    @PreAuthorize(Roles.SUPERVISOR)
    @PostMapping("/{id}/approve")
    public ResponseEntity<KitchenAccessRequestResponse> approve(@PathVariable String id) {
        return ResponseEntity.ok(toResponse(service.approve(id)));
    }

    @Operation(summary = "Rejeita um pedido, com motivo opcional")
    @PreAuthorize(Roles.SUPERVISOR)
    @PostMapping("/{id}/reject")
    public ResponseEntity<KitchenAccessRequestResponse> reject(@PathVariable String id,
                                                                @Valid @RequestBody(required = false) RejectKitchenAccessRequest body) {
        return ResponseEntity.ok(toResponse(service.reject(id, body == null ? null : body.reason())));
    }

    private KitchenAccessRequestResponse toResponse(KitchenAccessRequest request) {
        return KitchenAccessRequestResponse.from(request, service.pendingTtl());
    }
}
