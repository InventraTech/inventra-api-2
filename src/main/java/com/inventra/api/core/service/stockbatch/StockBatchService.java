package com.inventra.api.core.service.stockbatch;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inventra.api.core.service.ranking.StockRankingChanged;
import com.inventra.api.core.service.stockbatch.model.request.ConsumeProductRequest;
import com.inventra.api.core.service.stockbatch.model.request.RegisterStockEntryRequest;
import com.inventra.api.core.service.stockbatch.model.response.BatchConsumptionResponse;
import com.inventra.api.core.service.stockbatch.model.response.KitchenStockItemResponse;
import com.inventra.api.core.service.stockbatch.model.response.LowStockAlertResponse;
import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.product.Product;
import com.inventra.api.core.domain.product.ProductKitchenParameter;
import com.inventra.api.core.domain.stock.StockBatch;
import com.inventra.api.core.domain.stock.StockMovement;
import com.inventra.api.core.domain.stock.enums.StockBatchStatus;
import com.inventra.api.core.domain.stock.enums.StockMovementReason;
import com.inventra.api.core.domain.stock.enums.StockMovementType;
import com.inventra.api.core.domain.supplier.Supplier;
import com.inventra.api.infrastructure.exception.BusinessRuleException;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.KitchenRepository;
import com.inventra.api.infrastructure.repository.ProductKitchenParameterRepository;
import com.inventra.api.infrastructure.repository.ProductRepository;
import com.inventra.api.infrastructure.repository.StockBatchRepository;
import com.inventra.api.infrastructure.repository.StockMovementRepository;
import com.inventra.api.infrastructure.repository.SupplierRepository;
import com.inventra.api.infrastructure.security.KitchenAccessGuard;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StockBatchService implements StockBatchUseCase {

    private final StockBatchRepository repository;
    private final ProductRepository productRepository;
    private final KitchenRepository kitchenRepository;
    private final SupplierRepository supplierRepository;
    private final ProductKitchenParameterRepository productKitchenParameterRepository;
    private final StockMovementRepository movementRepository;
    private final KitchenAccessGuard accessGuard;
    private final ApplicationEventPublisher events;

    @Override
    @Transactional
    public StockBatch registerEntry(RegisterStockEntryRequest request) {
        requirePositive(request.initialQuantity());
        accessGuard.assertAccess(request.kitchenId());
        if (request.expirationDate() != null && request.expirationDate().isBefore(request.entryDate())) {
            throw new IllegalArgumentException("A data de validade não pode ser anterior à data de entrada.");
        }
        Product product = productRepository.findById(request.productId())
            .orElseThrow(() -> new ResourceNotFoundException("Produto não encontrado."));
        if (!product.isActive()) {
            throw new BusinessRuleException("Produto desativado não pode receber entrada de estoque.");
        }
        Kitchen kitchen = kitchenRepository.findById(request.kitchenId())
            .orElseThrow(() -> new ResourceNotFoundException("Cozinha não encontrada."));
        assertKitchenActive(kitchen);

        Supplier supplier = null;
        if (request.supplierId() != null) {
            supplier = supplierRepository.findById(request.supplierId())
                .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado."));
        }

        StockBatch batch = StockBatch.builder()
            .product(product)
            .kitchen(kitchen)
            .supplier(supplier)
            .batchNumber(request.batchNumber())
            .invoiceNumber(request.invoiceNumber())
            .initialQuantity(request.initialQuantity())
            .currentQuantity(request.initialQuantity())
            .entryDate(request.entryDate())
            .expirationDate(request.expirationDate())
            .unitPrice(request.unitPrice())
            .status(StockBatchStatus.ACTIVE)
            .build();

        StockBatch saved = repository.save(batch);
        // a entrada em lote existente é gravada pela procedure; a criação do lote, aqui
        recordMovement(saved, StockMovementType.ENTRY, saved.getInitialQuantity(), saved.getCurrentQuantity());
        events.publishEvent(new StockRankingChanged(request.kitchenId()));
        return saved;
    }

    @Override
    @Transactional
    public StockBatch consume(Integer batchId, BigDecimal quantity, StockMovementReason reason) {
        requirePositive(quantity);
        StockMovementReason writeOffReason = manualReason(reason);
        StockBatch batch = findAccessibleBatch(batchId);
        assertKitchenActive(batch.getKitchen());

        if (batch.getStatus() != StockBatchStatus.ACTIVE) {
            throw new BusinessRuleException("Só é possível dar baixa em lote ativo.");
        }
        // lote vencido não é usado, mas pode ser descartado (perda ou vencimento)
        if (isExpired(batch) && writeOffReason == StockMovementReason.USAGE) {
            throw new BusinessRuleException("Lote vencido não pode ser consumido.");
        }
        if (quantity.compareTo(batch.getCurrentQuantity()) > 0) {
            throw new BusinessRuleException("Quantidade solicitada maior que o saldo do lote.");
        }

        repository.callWriteOffStock(batchId, quantity, currentUserId(), writeOffReason.name());
        StockBatch updated = reload(batchId);
        events.publishEvent(new StockRankingChanged(updated.getKitchen().getId()));
        return updated;
    }

    @Override
    @Transactional
    public StockBatch restock(Integer batchId, BigDecimal quantity) {
        requirePositive(quantity);
        StockBatch batch = findAccessibleBatch(batchId);
        assertKitchenActive(batch.getKitchen());

        repository.callRegisterStockEntry(batchId, quantity, currentUserId());
        StockBatch updated = reload(batchId);
        events.publishEvent(new StockRankingChanged(updated.getKitchen().getId()));
        return updated;
    }

    @Override
    @Transactional
    public List<BatchConsumptionResponse> consumeProduct(ConsumeProductRequest request) {
        StockMovementReason reason = manualReason(request.reason());
        accessGuard.assertAccess(request.kitchenId());
        Kitchen kitchen = kitchenRepository.findById(request.kitchenId())
            .orElseThrow(() -> new ResourceNotFoundException("Cozinha não encontrada."));
        assertKitchenActive(kitchen);
        if (!productRepository.existsById(request.productId())) {
            throw new ResourceNotFoundException("Produto não encontrado.");
        }
        return consumeByExpiration(request.kitchenId(), request.productId(), request.quantity(), reason, null);
    }

    @Override
    @Transactional
    public void consumeForRequisition(Integer kitchenId, Integer productId, BigDecimal quantity, Integer requisitionId) {
        consumeByExpiration(kitchenId, productId, quantity, StockMovementReason.REQUISITION, requisitionId);
    }

    // Só lotes ACTIVE e dentro da validade, em FIFO por validade, lidos com lock. Se o saldo não cobre a
    // quantidade, a exceção desfaz as baixas já feitas nesta transação: nada é baixado pela metade.
    private List<BatchConsumptionResponse> consumeByExpiration(Integer kitchenId, Integer productId,
                                                               BigDecimal quantity, StockMovementReason reason,
                                                               Integer requisitionId) {
        requirePositive(quantity);
        accessGuard.assertAccess(kitchenId);
        UUID userId = currentUserId();
        List<StockBatch> batches = repository.findUsableForConsumption(kitchenId, productId, LocalDate.now());

        List<BatchConsumptionResponse> consumed = new ArrayList<>();
        BigDecimal remaining = quantity;
        for (StockBatch batch : batches) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }

            BigDecimal taken = batch.getCurrentQuantity().min(remaining);
            if (taken.compareTo(BigDecimal.ZERO) > 0) {
                if (requisitionId == null) {
                    repository.callWriteOffStock(batch.getId(), taken, userId, reason.name());
                } else {
                    repository.callWriteOffStockForRequisition(batch.getId(), taken, userId, reason.name(), requisitionId);
                }
                consumed.add(BatchConsumptionResponse.of(batch, taken));
            }

            remaining = remaining.subtract(taken);
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessRuleException("Estoque insuficiente para atender a quantidade solicitada.");
        }
        events.publishEvent(new StockRankingChanged(kitchenId));
        return consumed;
    }

    // Lê o lote com lock (SELECT ... FOR UPDATE): uma baixa ou outro ajuste simultâneo no mesmo lote espera
    // esta transação terminar, em vez de um sobrescrever o saldo gravado pelo outro.
    @Override
    @Transactional
    public StockBatch adjust(Integer batchId, BigDecimal newQuantity) {
        if (newQuantity == null || newQuantity.signum() < 0) {
            throw new IllegalArgumentException("Quantidade de ajuste deve ser zero ou positiva.");
        }
        StockBatch batch = repository.findByIdForUpdate(batchId)
            .orElseThrow(() -> new ResourceNotFoundException("Lote não encontrado."));
        accessGuard.assertAccess(batch.getKitchen().getId());
        assertKitchenActive(batch.getKitchen());

        BigDecimal difference = newQuantity.subtract(batch.getCurrentQuantity());
        batch.setCurrentQuantity(newQuantity);
        if (newQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            batch.setStatus(StockBatchStatus.WRITTEN_OFF);
        } else if (batch.getStatus() == StockBatchStatus.WRITTEN_OFF) {
            batch.setStatus(StockBatchStatus.ACTIVE);
        }

        StockBatch saved = repository.save(batch);
        // o ajuste guarda a diferença, com sinal; ajuste para o mesmo saldo não é movimentação
        if (difference.signum() != 0) {
            recordMovement(saved, StockMovementType.ADJUSTMENT, difference, newQuantity);
        }
        events.publishEvent(new StockRankingChanged(batch.getKitchen().getId()));
        return saved;
    }

    @Override
    public ProductStock summarizeProductStock(Integer kitchenId, Integer productId) {
        accessGuard.assertAccess(kitchenId);
        Object[] row = repository.summarizeUsableForProduct(kitchenId, productId, LocalDate.now()).get(0);
        return new ProductStock(
            row[0] == null ? BigDecimal.ZERO : (BigDecimal) row[0],
            ((Number) row[1]).intValue(),
            (LocalDate) row[2]);
    }

    @Override
    public List<StockBatch> findExpiringSoon(Integer kitchenId, int days) {
        accessGuard.assertAccess(kitchenId);
        if (days < 0) {
            throw new IllegalArgumentException("O parâmetro days não pode ser negativo.");
        }
        LocalDate today = LocalDate.now();
        return repository.findByKitchenIdAndStatusAndExpirationDateBetween(
            kitchenId, StockBatchStatus.ACTIVE, today, today.plusDays(days));
    }

    @Override
    public List<LowStockAlertResponse> findLowStock() {
        Integer kitchenId = accessGuard.currentKitchenId();
        if (kitchenId == null) {
            return List.of();
        }
        LocalDate today = LocalDate.now();
        // duas consultas, independente de quantos produtos estão abaixo: os parâmetros abaixo do mínimo
        // (já com produto e unidade) e o saldo de todos eles de uma vez
        var belowMinimum = productKitchenParameterRepository.findBelowMinimum(kitchenId, today);
        if (belowMinimum.isEmpty()) {
            return List.of();
        }
        Map<Integer, BigDecimal> balances = new HashMap<>();
        repository.sumUsableQuantityByProduct(kitchenId,
                belowMinimum.stream().map(parameter -> parameter.getProduct().getId()).toList(), today)
            .forEach(row -> balances.put((Integer) row[0], (BigDecimal) row[1]));
        return belowMinimum.stream()
            .map(parameter -> LowStockAlertResponse.of(
                kitchenId,
                parameter.getProduct(),
                balances.getOrDefault(parameter.getProduct().getId(), BigDecimal.ZERO),
                parameter.getMinStock()))
            .toList();
    }

    @Override
    public List<StockBatch> listByKitchen(Integer kitchenId) {
        accessGuard.assertAccess(kitchenId);
        return repository.findByKitchenId(kitchenId);
    }

    @Override
    public List<StockBatch> listByKitchenAndProduct(Integer kitchenId, Integer productId) {
        accessGuard.assertAccess(kitchenId);
        return repository.findByKitchenIdAndProductId(kitchenId, productId);
    }

    @Override
    public List<KitchenStockItemResponse> summarizeKitchenStock(Integer kitchenId, Integer categoryId, String name) {
        accessGuard.assertAccess(kitchenId);
        // duas consultas, independente do tamanho da cozinha: os produtos ligados a ela (com o parâmetro de
        // estoque, se houver) e o resumo dos lotes utilizáveis de todos de uma vez
        Map<Integer, Object[]> usable = new HashMap<>();
        repository.summarizeUsableByProduct(kitchenId, LocalDate.now()).forEach(row -> usable.put((Integer) row[0], row));
        return productRepository.findKitchenProducts(kitchenId, name == null ? "" : name.trim(), categoryId).stream()
            .map(row -> {
                Product product = (Product) row[0];
                var parameter = (ProductKitchenParameter) row[1];
                Object[] summary = usable.get(product.getId());
                return KitchenStockItemResponse.of(
                    product,
                    summary == null ? BigDecimal.ZERO : (BigDecimal) summary[1],
                    summary == null ? 0 : ((Number) summary[2]).intValue(),
                    summary == null ? null : (LocalDate) summary[3],
                    parameter == null ? null : parameter.getMinStock());
            })
            .toList();
    }

    @Override
    public List<StockBatch> listByProduct(Integer productId) {
        Integer kitchenId = accessGuard.currentKitchenId();
        return kitchenId == null ? List.of() : repository.findByKitchenIdAndProductId(kitchenId, productId);
    }

    // Motivo de uma baixa feita por uma pessoa: USAGE se não vier; REQUISITION só nasce da aprovação.
    private static StockMovementReason manualReason(StockMovementReason reason) {
        if (reason == StockMovementReason.REQUISITION) {
            throw new IllegalArgumentException("O motivo REQUISITION é reservado à aprovação de requisição.");
        }
        return reason == null ? StockMovementReason.USAGE : reason;
    }

    private UUID currentUserId() {
        return accessGuard.currentUser().getId();
    }

    private void recordMovement(StockBatch batch, StockMovementType type, BigDecimal quantity, BigDecimal balanceAfter) {
        movementRepository.save(StockMovement.builder()
            .batch(batch)
            .user(accessGuard.currentUser())
            .type(type)
            .quantity(quantity)
            .balanceAfter(balanceAfter)
            .build());
    }

    private static boolean isExpired(StockBatch batch) {
        return batch.getExpirationDate() != null && batch.getExpirationDate().isBefore(LocalDate.now());
    }

    private static void assertKitchenActive(Kitchen kitchen) {
        if (!Boolean.TRUE.equals(kitchen.getActive())) {
            throw new BusinessRuleException("Cozinha desativada: reative a cozinha para movimentar o estoque.");
        }
    }

    private StockBatch findAccessibleBatch(Integer batchId) {
        StockBatch batch = repository.findById(batchId)
            .orElseThrow(() -> new ResourceNotFoundException("Lote não encontrado."));
        accessGuard.assertAccess(batch.getKitchen().getId());
        return batch;
    }

    private StockBatch reload(Integer batchId) {
        return repository.findById(batchId)
            .orElseThrow(() -> new ResourceNotFoundException("Lote não encontrado."));
    }

    private static void requirePositive(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("Quantidade deve ser positiva.");
        }
    }
}
