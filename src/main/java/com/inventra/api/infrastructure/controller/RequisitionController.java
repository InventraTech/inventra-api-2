package com.inventra.api.infrastructure.controller;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.inventra.api.core.domain.requisition.Requisition;
import com.inventra.api.core.domain.requisition.enums.RequisitionStatus;
import com.inventra.api.core.service.requisition.RequisitionItemsSummary;
import com.inventra.api.core.service.requisition.RequisitionUseCase;
import com.inventra.api.core.service.requisition.model.request.AddRequisitionItemRequest;
import com.inventra.api.core.service.requisition.model.request.CancelRequisitionRequest;
import com.inventra.api.core.service.requisition.model.request.CreateRequisitionRequest;
import com.inventra.api.core.service.requisition.model.request.RejectRequisitionRequest;
import com.inventra.api.core.service.requisition.model.response.RequisitionItemResponse;
import com.inventra.api.core.service.requisition.model.response.RequisitionResponse;
import com.inventra.api.infrastructure.security.Roles;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@PreAuthorize(Roles.REQUISITION)
@RestController
@Tag(name = "Requisições", description = "Requisições de consumo, transferência e compra")
@RequestMapping("/api/requisitions")
@RequiredArgsConstructor
public class RequisitionController {

    private final RequisitionUseCase useCase;

    @Operation(summary = "Cria uma requisição em rascunho (DRAFT)")
    @PostMapping
    public ResponseEntity<RequisitionResponse> create(@Valid @RequestBody CreateRequisitionRequest request) {
        RequisitionResponse response = toResponse(useCase.create(request));
        return ResponseEntity.created(URI.create("/api/requisitions/" + response.id())).body(response);
    }

    @Operation(summary = "Lista requisições combinando os filtros de cozinha, status e solicitante",
            description = "Informe pelo menos um filtro. Sem kitchenId, vale a cozinha do usuário logado. "
                    + "Cada requisição traz o resumo dos itens (itemCount, estimatedTotal e itemNames).")
    @GetMapping
    public ResponseEntity<List<RequisitionResponse>> list(@RequestParam(required = false) Integer kitchenId,
                                                            @RequestParam(required = false) RequisitionStatus status,
                                                            @RequestParam(required = false) UUID requesterId) {
        if (kitchenId == null && status == null && requesterId == null) {
            throw new IllegalArgumentException("Informe kitchenId, status ou requesterId.");
        }
        List<Requisition> requisitions = useCase.list(kitchenId, status, requesterId);
        Map<Integer, RequisitionItemsSummary> items = useCase.summarizeItems(requisitions);

        List<RequisitionResponse> responses = requisitions.stream()
                .map(requisition -> RequisitionResponse.fromEntity(requisition,
                        items.getOrDefault(requisition.getId(), RequisitionItemsSummary.EMPTY)))
                .toList();
        return ResponseEntity.ok(responses);
    }

    @Operation(summary = "Busca uma requisição pelo id")
    @GetMapping("/{id}")
    public ResponseEntity<RequisitionResponse> findById(@PathVariable Integer id) {
        return ResponseEntity.ok(toResponse(useCase.findById(id)));
    }

    @Operation(summary = "Adiciona um item à requisição (só em rascunho)")
    @PostMapping("/{id}/items")
    public ResponseEntity<RequisitionResponse> addItem(@PathVariable Integer id,
                                                        @Valid @RequestBody AddRequisitionItemRequest request) {
        return ResponseEntity.ok(toResponse(useCase.addItem(id, request)));
    }

    @Operation(summary = "Remove um item da requisição (só em rascunho)")
    @DeleteMapping("/{id}/items/{itemId}")
    public ResponseEntity<RequisitionResponse> removeItem(@PathVariable Integer id, @PathVariable Integer itemId) {
        return ResponseEntity.ok(toResponse(useCase.removeItem(id, itemId)));
    }

    @Operation(summary = "Lista os itens da requisição")
    @GetMapping("/{id}/items")
    public ResponseEntity<List<RequisitionItemResponse>> listItems(@PathVariable Integer id) {
        List<RequisitionItemResponse> responses = useCase.listItems(id).stream()
                .map(RequisitionItemResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @Operation(summary = "Envia a requisição para análise (DRAFT -> UNDER_REVIEW)")
    @PatchMapping("/{id}/submit")
    public ResponseEntity<RequisitionResponse> submit(@PathVariable Integer id) {
        return ResponseEntity.ok(toResponse(useCase.submit(id)));
    }

    @Operation(summary = "Aprova a requisição em análise (procedure sp_approve_requisition)")
    @PreAuthorize(Roles.SUPERVISOR)
    @PatchMapping("/{id}/approve")
    public ResponseEntity<RequisitionResponse> approve(@PathVariable Integer id) {
        return ResponseEntity.ok(toResponse(useCase.approve(id)));
    }

    @Operation(summary = "Rejeita a requisição em análise, com motivo (procedure sp_reject_requisition)")
    @PreAuthorize(Roles.SUPERVISOR)
    @PatchMapping("/{id}/reject")
    public ResponseEntity<RequisitionResponse> reject(@PathVariable Integer id,
                                                       @Valid @RequestBody RejectRequisitionRequest request) {
        return ResponseEntity.ok(toResponse(useCase.reject(id, request.reason())));
    }

    @Operation(summary = "Cancela a requisição com motivo (procedure sp_cancel_requisition)",
            description = "Cancelar uma requisição já aprovada é só do supervisor e devolve ao estoque o que a "
                    + "aprovação tinha baixado.")
    @PatchMapping("/{id}/cancel")
    public ResponseEntity<RequisitionResponse> cancel(@PathVariable Integer id,
                                                       @Valid @RequestBody CancelRequisitionRequest request) {
        return ResponseEntity.ok(toResponse(useCase.cancel(id, request.reason())));
    }

    private RequisitionResponse toResponse(Requisition requisition) {
        RequisitionItemsSummary items = useCase.summarizeItems(List.of(requisition))
                .getOrDefault(requisition.getId(), RequisitionItemsSummary.EMPTY);
        return RequisitionResponse.fromEntity(requisition, items);
    }
}
