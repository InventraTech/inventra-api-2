package com.inventra.api.core.service.stockbatch;

import java.math.BigDecimal;
import java.util.List;

import com.inventra.api.core.service.stockbatch.model.request.ConsumeProductRequest;
import com.inventra.api.core.service.stockbatch.model.request.RegisterStockEntryRequest;
import com.inventra.api.core.service.stockbatch.model.response.BatchConsumptionResponse;
import com.inventra.api.core.service.stockbatch.model.response.KitchenStockItemResponse;
import com.inventra.api.core.service.stockbatch.model.response.LowStockAlertResponse;
import com.inventra.api.core.domain.stock.StockBatch;
import com.inventra.api.core.domain.stock.enums.StockMovementReason;

public interface StockBatchUseCase {

    StockBatch registerEntry(RegisterStockEntryRequest request);

    // chama sp_write_off_stock; reason nulo = USAGE. REQUISITION é reservado à aprovação de requisição.
    StockBatch consume(Integer batchId, BigDecimal quantity, StockMovementReason reason);

    // baixa por produto (scan): tira dos lotes que vencem primeiro e devolve os lotes afetados
    List<BatchConsumptionResponse> consumeProduct(ConsumeProductRequest request);

    // chama sp_register_stock_entry: soma quantity ao saldo de um lote existente e o reativa
    StockBatch restock(Integer batchId, BigDecimal quantity);

    // baixa por FEFO (lote mais próximo do vencimento primeiro), com motivo REQUISITION — usado pelo fluxo
    // de aprovação de requisição
    void consumeForRequisition(Integer kitchenId, Integer productId, BigDecimal quantity, Integer requisitionId);

    // saldo utilizável (lotes ativos e não vencidos) de um produto na cozinha
    ProductStock summarizeProductStock(Integer kitchenId, Integer productId);

    StockBatch adjust(Integer batchId, BigDecimal newQuantity);

    List<StockBatch> findExpiringSoon(Integer kitchenId, int days);

    List<LowStockAlertResponse> findLowStock();

    List<StockBatch> listByKitchen(Integer kitchenId);

    List<StockBatch> listByKitchenAndProduct(Integer kitchenId, Integer productId);

    // produtos ativos ligados à cozinha (com lote ou com parâmetro de estoque) e o saldo utilizável de cada um
    List<KitchenStockItemResponse> summarizeKitchenStock(Integer kitchenId, Integer categoryId, String name);

    List<StockBatch> listByProduct(Integer productId);

    record ProductStock(BigDecimal totalQuantity, int activeBatches, java.time.LocalDate nextExpirationDate) {
    }
}
