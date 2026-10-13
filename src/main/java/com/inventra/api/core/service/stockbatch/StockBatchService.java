package com.inventra.api.core.service.stockbatch;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inventra.api.core.service.stockbatch.model.request.RegisterStockEntryRequest;
import com.inventra.api.core.service.stockbatch.model.response.LowStockAlertResponse;
import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.product.Product;
import com.inventra.api.core.domain.product.ProductKitchenParameter;
import com.inventra.api.core.domain.stock.StockBatch;
import com.inventra.api.core.domain.stock.enums.StockBatchStatus;
import com.inventra.api.core.domain.supplier.Supplier;
import com.inventra.api.infrastructure.exception.BusinessRuleException;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.KitchenRepository;
import com.inventra.api.infrastructure.repository.ProductKitchenParameterRepository;
import com.inventra.api.infrastructure.repository.ProductRepository;
import com.inventra.api.infrastructure.repository.StockBatchRepository;
import com.inventra.api.infrastructure.repository.SupplierRepository;
import com.inventra.api.infrastructure.security.KitchenAccessGuard;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class    StockBatchService implements StockBatchUseCase {

    private final StockBatchRepository repository;
    private final ProductRepository productRepository;
    private final KitchenRepository kitchenRepository;
    private final SupplierRepository supplierRepository;
    private final ProductKitchenParameterRepository productKitchenParameterRepository;
    private final KitchenAccessGuard accessGuard;

    @Override
    public StockBatch registerEntry(RegisterStockEntryRequest request) {
        accessGuard.assertAccess(request.kitchenId());
        Product product = productRepository.findById(request.productId())
            .orElseThrow(() -> new ResourceNotFoundException("Produto não encontrado."));
        Kitchen kitchen = kitchenRepository.findById(request.kitchenId())
            .orElseThrow(() -> new ResourceNotFoundException("Cozinha não encontrada."));

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

        return repository.save(batch);
    }

    @Override
    @Transactional
    public StockBatch consume(Integer batchId, BigDecimal quantity) {
        StockBatch batch = findAccessibleBatch(batchId);

        if (quantity.compareTo(batch.getCurrentQuantity()) > 0) {
            throw new BusinessRuleException("Quantidade solicitada maior que o saldo do lote.");
        }

        repository.callWriteOffStock(batchId, quantity);
        return reload(batchId);
    }

    @Override
    @Transactional
    public StockBatch restock(Integer batchId, BigDecimal quantity) {
        findAccessibleBatch(batchId);

        repository.callRegisterStockEntry(batchId, quantity);
        return reload(batchId);
    }

    @Override
    @Transactional
    public void consumeForProduct(Integer kitchenId, Integer productId, BigDecimal quantity) {
        accessGuard.assertAccess(kitchenId);
        List<StockBatch> batches = repository
            .findByKitchenIdAndProductIdAndStatusOrderByExpirationDateAscEntryDateAsc(
                kitchenId, productId, StockBatchStatus.ACTIVE);

        BigDecimal remaining = quantity;
        for (StockBatch batch : batches) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }

            BigDecimal taken = batch.getCurrentQuantity().min(remaining);
            if (taken.compareTo(BigDecimal.ZERO) > 0) {
                repository.callWriteOffStock(batch.getId(), taken);
            }

            remaining = remaining.subtract(taken);
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessRuleException("Estoque insuficiente para atender a quantidade solicitada.");
        }
    }

    @Override
    public StockBatch adjust(Integer batchId, BigDecimal newQuantity) {
        StockBatch batch = repository.findById(batchId)
            .orElseThrow(() -> new ResourceNotFoundException("Lote não encontrado."));
        accessGuard.assertAccess(batch.getKitchen().getId());

        batch.setCurrentQuantity(newQuantity);
        if (newQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            batch.setStatus(StockBatchStatus.WRITTEN_OFF);
        } else if (batch.getStatus() == StockBatchStatus.WRITTEN_OFF) {
            batch.setStatus(StockBatchStatus.ACTIVE);
        }

        return repository.save(batch);
    }

    @Override
    public List<StockBatch> findExpiringSoon(Integer kitchenId, int days) {
        accessGuard.assertAccess(kitchenId);
        LocalDate today = LocalDate.now();
        return repository.findByKitchenIdAndStatusAndExpirationDateBetween(
            kitchenId, StockBatchStatus.ACTIVE, today, today.plusDays(days));
    }

    @Override
    public List<LowStockAlertResponse> findLowStock() {
        List<LowStockAlertResponse> alerts = new ArrayList<>();

        for (ProductKitchenParameter parameter : productKitchenParameterRepository.findAll()) {
            if (!accessGuard.hasAccess(parameter.getKitchen().getId())) {
                continue;
            }

            BigDecimal current = repository.sumActiveQuantity(
                parameter.getProduct().getId(), parameter.getKitchen().getId());

            if (current.compareTo(parameter.getMinStock()) < 0) {
                alerts.add(new LowStockAlertResponse(
                    parameter.getKitchen().getId(), parameter.getProduct().getId(), current, parameter.getMinStock()));
            }
        }

        return alerts;
    }

    @Override
    public List<StockBatch> listByKitchen(Integer kitchenId) {
        accessGuard.assertAccess(kitchenId);
        return repository.findByKitchenId(kitchenId);
    }

    @Override
    public List<StockBatch> listByProduct(Integer productId) {
        return repository.findByProductId(productId).stream()
            .filter(batch -> accessGuard.hasAccess(batch.getKitchen().getId()))
            .toList();
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
}
