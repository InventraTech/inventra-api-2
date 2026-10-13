package com.inventra.api.infrastructure.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.inventra.api.core.domain.stock.StockBatch;
import com.inventra.api.core.service.stockbatch.StockBatchUseCase;
import com.inventra.api.core.service.stockbatch.model.request.AdjustStockRequest;
import com.inventra.api.core.service.stockbatch.model.request.ConsumeStockRequest;
import com.inventra.api.core.service.stockbatch.model.request.RegisterStockEntryRequest;
import com.inventra.api.core.service.stockbatch.model.request.RestockRequest;
import com.inventra.api.core.service.stockbatch.model.response.LowStockAlertResponse;
import com.inventra.api.core.service.stockbatch.model.response.StockBatchResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/stock-batches")
@RequiredArgsConstructor
public class StockBatchController {

    private final StockBatchUseCase useCase;

    @PostMapping
    public ResponseEntity<StockBatchResponse> registerEntry(@Valid @RequestBody RegisterStockEntryRequest request) {
        StockBatch created = useCase.registerEntry(request);
        StockBatchResponse response = StockBatchResponse.fromEntity(created);
        return ResponseEntity.created(URI.create("/api/stock-batches/" + response.id())).body(response);
    }

    @GetMapping
    public ResponseEntity<List<StockBatchResponse>> list(@RequestParam(required = false) Integer kitchenId,
                                                           @RequestParam(required = false) Integer productId) {
        List<StockBatch> batches;
        if (kitchenId != null) {
            batches = useCase.listByKitchen(kitchenId);
        } else if (productId != null) {
            batches = useCase.listByProduct(productId);
        } else {
            throw new IllegalArgumentException("Informe kitchenId ou productId.");
        }

        List<StockBatchResponse> responses = batches.stream()
                .map(StockBatchResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/expiring-soon")
    public ResponseEntity<List<StockBatchResponse>> findExpiringSoon(@RequestParam Integer kitchenId,
                                                                       @RequestParam int days) {
        List<StockBatchResponse> responses = useCase.findExpiringSoon(kitchenId, days).stream()
                .map(StockBatchResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/low-stock")
    public ResponseEntity<List<LowStockAlertResponse>> findLowStock() {
        return ResponseEntity.ok(useCase.findLowStock());
    }

    @PatchMapping("/{id}/consume")
    public ResponseEntity<StockBatchResponse> consume(@PathVariable Integer id,
                                                       @Valid @RequestBody ConsumeStockRequest request) {
        StockBatch batch = useCase.consume(id, request.quantity());
        return ResponseEntity.ok(StockBatchResponse.fromEntity(batch));
    }

    @PatchMapping("/{id}/restock")
    public ResponseEntity<StockBatchResponse> restock(@PathVariable Integer id,
                                                       @Valid @RequestBody RestockRequest request) {
        StockBatch batch = useCase.restock(id, request.quantity());
        return ResponseEntity.ok(StockBatchResponse.fromEntity(batch));
    }

    @PatchMapping("/{id}/adjust")
    public ResponseEntity<StockBatchResponse> adjust(@PathVariable Integer id,
                                                       @Valid @RequestBody AdjustStockRequest request) {
        StockBatch batch = useCase.adjust(id, request.newQuantity());
        return ResponseEntity.ok(StockBatchResponse.fromEntity(batch));
    }
}
