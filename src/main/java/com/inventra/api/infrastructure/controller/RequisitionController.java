package com.inventra.api.infrastructure.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.inventra.api.core.domain.requisition.Requisition;
import com.inventra.api.core.domain.requisition.enums.RequisitionStatus;
import com.inventra.api.core.service.requisition.RequisitionUseCase;
import com.inventra.api.core.service.requisition.model.request.AddRequisitionItemRequest;
import com.inventra.api.core.service.requisition.model.request.ApproveRequisitionRequest;
import com.inventra.api.core.service.requisition.model.request.CancelRequisitionRequest;
import com.inventra.api.core.service.requisition.model.request.CreateRequisitionRequest;
import com.inventra.api.core.service.requisition.model.request.RejectRequisitionRequest;
import com.inventra.api.core.service.requisition.model.response.RequisitionItemResponse;
import com.inventra.api.core.service.requisition.model.response.RequisitionResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/requisitions")
@RequiredArgsConstructor
public class RequisitionController {

    private final RequisitionUseCase useCase;

    @PostMapping
    public ResponseEntity<RequisitionResponse> create(@Valid @RequestBody CreateRequisitionRequest request) {
        Requisition created = useCase.create(request);
        RequisitionResponse response = RequisitionResponse.fromEntity(created);
        return ResponseEntity.created(URI.create("/api/requisitions/" + response.id())).body(response);
    }

    @GetMapping
    public ResponseEntity<List<RequisitionResponse>> list(@RequestParam(required = false) Integer kitchenId,
                                                            @RequestParam(required = false) RequisitionStatus status,
                                                            @RequestParam(required = false) UUID requesterId) {
        List<Requisition> requisitions;
        if (kitchenId != null) {
            requisitions = useCase.listByKitchen(kitchenId);
        } else if (status != null) {
            requisitions = useCase.listByStatus(status);
        } else if (requesterId != null) {
            requisitions = useCase.listByRequester(requesterId);
        } else {
            throw new IllegalArgumentException("Informe kitchenId, status ou requesterId.");
        }

        List<RequisitionResponse> responses = requisitions.stream()
                .map(RequisitionResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @PostMapping("/{id}/items")
    public ResponseEntity<RequisitionResponse> addItem(@PathVariable Integer id,
                                                        @Valid @RequestBody AddRequisitionItemRequest request) {
        Requisition updated = useCase.addItem(id, request);
        return ResponseEntity.ok(RequisitionResponse.fromEntity(updated));
    }

    @DeleteMapping("/{id}/items/{itemId}")
    public ResponseEntity<RequisitionResponse> removeItem(@PathVariable Integer id, @PathVariable Integer itemId) {
        Requisition updated = useCase.removeItem(id, itemId);
        return ResponseEntity.ok(RequisitionResponse.fromEntity(updated));
    }

    @GetMapping("/{id}/items")
    public ResponseEntity<List<RequisitionItemResponse>> listItems(@PathVariable Integer id) {
        List<RequisitionItemResponse> responses = useCase.listItems(id).stream()
                .map(RequisitionItemResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @PatchMapping("/{id}/submit")
    public ResponseEntity<RequisitionResponse> submit(@PathVariable Integer id) {
        return ResponseEntity.ok(RequisitionResponse.fromEntity(useCase.submit(id)));
    }

    @PatchMapping("/{id}/approve")
    public ResponseEntity<RequisitionResponse> approve(@PathVariable Integer id,
                                                        @Valid @RequestBody ApproveRequisitionRequest request) {
        Requisition approved = useCase.approve(id, request.approverId());
        return ResponseEntity.ok(RequisitionResponse.fromEntity(approved));
    }

    @PatchMapping("/{id}/reject")
    public ResponseEntity<RequisitionResponse> reject(@PathVariable Integer id,
                                                       @Valid @RequestBody RejectRequisitionRequest request) {
        Requisition rejected = useCase.reject(id, request.reason());
        return ResponseEntity.ok(RequisitionResponse.fromEntity(rejected));
    }

    @PatchMapping("/{id}/cancel")
    public ResponseEntity<RequisitionResponse> cancel(@PathVariable Integer id,
                                                       @Valid @RequestBody CancelRequisitionRequest request) {
        Requisition cancelled = useCase.cancel(id, request.reason());
        return ResponseEntity.ok(RequisitionResponse.fromEntity(cancelled));
    }
}
