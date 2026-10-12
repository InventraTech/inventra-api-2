package com.inventra.api.infrastructure.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.inventra.api.core.domain.stock.StockBatch;
import com.inventra.api.core.service.stockbatch.StockBatchUseCase;
import com.inventra.api.core.service.stockbatch.model.request.AdjustStockRequest;
import com.inventra.api.core.service.stockbatch.model.request.ConsumeProductRequest;
import com.inventra.api.core.service.stockbatch.model.request.ConsumeStockRequest;
import com.inventra.api.core.service.stockbatch.model.request.RegisterStockEntryRequest;
import com.inventra.api.core.service.stockbatch.model.request.RestockRequest;
import com.inventra.api.core.service.stockbatch.model.response.BatchConsumptionResponse;
import com.inventra.api.core.service.stockbatch.model.response.LowStockAlertResponse;
import com.inventra.api.core.service.stockbatch.model.response.StockBatchResponse;
import com.inventra.api.infrastructure.security.Roles;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@PreAuthorize(Roles.STOCK)
@RestController
@Tag(name = "Lotes de estoque", description = "Entradas, baixas e consultas de lotes")
@RequestMapping("/api/stock-batches")
@RequiredArgsConstructor
public class StockBatchController {

    private final StockBatchUseCase useCase;

    @Operation(summary = "Registra a entrada de um lote")
    @PostMapping
    public ResponseEntity<StockBatchResponse> registerEntry(@Valid @RequestBody RegisterStockEntryRequest request) {
        StockBatch created = useCase.registerEntry(request);
        StockBatchResponse response = StockBatchResponse.fromEntity(created);
        return ResponseEntity.created(URI.create("/api/stock-batches/" + response.id())).body(response);
    }

    @Operation(summary = "Lista lotes por cozinha e/ou produto")
    @GetMapping
    public ResponseEntity<List<StockBatchResponse>> list(@RequestParam(required = false) Integer kitchenId,
                                                           @RequestParam(required = false) Integer productId) {
        List<StockBatch> batches;
        if (kitchenId != null && productId != null) {
            batches = useCase.listByKitchenAndProduct(kitchenId, productId);
        } else if (kitchenId != null) {
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

    @Operation(summary = "Lista lotes que vencem nos próximos dias")
    @GetMapping("/expiring-soon")
    public ResponseEntity<List<StockBatchResponse>> findExpiringSoon(@RequestParam Integer kitchenId,
                                                                       @RequestParam int days) {
        List<StockBatchResponse> responses = useCase.findExpiringSoon(kitchenId, days).stream()
                .map(StockBatchResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @Operation(summary = "Lista produtos abaixo do estoque mínimo")
    @GetMapping("/low-stock")
    public ResponseEntity<List<LowStockAlertResponse>> findLowStock() {
        return ResponseEntity.ok(useCase.findLowStock());
    }

    @Operation(summary = "Baixa quantidade de um produto, tirando dos lotes que vencem primeiro",
            description = "Devolve os lotes afetados com o saldo novo. Se o saldo da cozinha não cobre a "
                    + "quantidade, responde 409 e nada é baixado. reason: USAGE (padrão), LOSS ou EXPIRED.")
    @PostMapping("/consume")
    public ResponseEntity<List<BatchConsumptionResponse>> consumeProduct(
            @Valid @RequestBody ConsumeProductRequest request) {
        return ResponseEntity.ok(useCase.consumeProduct(request));
    }

    @Operation(summary = "Baixa quantidade do lote (procedure sp_write_off_stock)")
    @PatchMapping("/{id}/consume")
    public ResponseEntity<StockBatchResponse> consume(@PathVariable Integer id,
                                                       @Valid @RequestBody ConsumeStockRequest request) {
        StockBatch batch = useCase.consume(id, request.quantity(), request.reason());
        return ResponseEntity.ok(StockBatchResponse.fromEntity(batch));
    }

    @Operation(summary = "Soma quantidade ao lote (procedure sp_register_stock_entry)")
    @PatchMapping("/{id}/restock")
    public ResponseEntity<StockBatchResponse> restock(@PathVariable Integer id,
                                                       @Valid @RequestBody RestockRequest request) {
        StockBatch batch = useCase.restock(id, request.quantity());
        return ResponseEntity.ok(StockBatchResponse.fromEntity(batch));
    }

    @Operation(summary = "Ajusta a quantidade do lote")
    @PatchMapping("/{id}/adjust")
    public ResponseEntity<StockBatchResponse> adjust(@PathVariable Integer id,
                                                       @Valid @RequestBody AdjustStockRequest request) {
        StockBatch batch = useCase.adjust(id, request.newQuantity());
        return ResponseEntity.ok(StockBatchResponse.fromEntity(batch));
    }
}
