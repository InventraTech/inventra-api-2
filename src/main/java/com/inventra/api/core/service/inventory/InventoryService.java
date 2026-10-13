package com.inventra.api.core.service.inventory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inventra.api.core.domain.inventory.Inventory;
import com.inventra.api.core.domain.inventory.InventoryCount;
import com.inventra.api.core.domain.inventory.enums.InventoryStatus;
import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.stock.StockBatch;
import com.inventra.api.core.domain.user.User;
import com.inventra.api.core.service.inventory.model.request.OpenInventoryRequest;
import com.inventra.api.core.service.inventory.model.request.RegisterInventoryCountRequest;
import com.inventra.api.core.service.stockbatch.StockBatchUseCase;
import com.inventra.api.infrastructure.exception.BusinessRuleException;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.InventoryCountRepository;
import com.inventra.api.infrastructure.repository.InventoryRepository;
import com.inventra.api.infrastructure.repository.KitchenRepository;
import com.inventra.api.infrastructure.repository.StockBatchRepository;
import com.inventra.api.infrastructure.repository.UserRepository;
import com.inventra.api.infrastructure.security.KitchenAccessGuard;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class InventoryService implements InventoryUseCase {

    private final InventoryRepository repository;
    private final InventoryCountRepository countRepository;
    private final KitchenRepository kitchenRepository;
    private final UserRepository userRepository;
    private final StockBatchRepository stockBatchRepository;
    private final StockBatchUseCase stockBatchUseCase;
    private final KitchenAccessGuard accessGuard;

    @Override
    public Inventory open(OpenInventoryRequest request) {
        accessGuard.assertAccess(request.kitchenId());
        if (repository.existsByKitchenIdAndStatus(request.kitchenId(), InventoryStatus.OPEN)) {
            throw new BusinessRuleException("Já existe um inventário em aberto para essa cozinha.");
        }

        Kitchen kitchen = kitchenRepository.findById(request.kitchenId())
            .orElseThrow(() -> new ResourceNotFoundException("Cozinha não encontrada."));
        User responsible = userRepository.findById(request.responsibleId())
            .orElseThrow(() -> new ResourceNotFoundException("Usuário responsável não encontrado."));

        Inventory inventory = Inventory.builder()
            .kitchen(kitchen)
            .responsible(responsible)
            .status(InventoryStatus.OPEN)
            .note(request.note())
            .build();

        return repository.save(inventory);
    }

    @Override
    public Inventory findById(Integer id) {
        Inventory inventory = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Inventário não encontrado."));
        accessGuard.assertAccess(inventory.getKitchen().getId());
        return inventory;
    }

    @Override
    public List<Inventory> listByKitchen(Integer kitchenId) {
        accessGuard.assertAccess(kitchenId);
        return repository.findByKitchenId(kitchenId);
    }

    @Override
    public InventoryCount registerCount(Integer inventoryId, RegisterInventoryCountRequest request) {
        Inventory inventory = findOpenInventory(inventoryId);

        StockBatch batch = stockBatchRepository.findById(request.batchId())
            .orElseThrow(() -> new ResourceNotFoundException("Lote não encontrado."));

        BigDecimal registeredQuantity = batch.getCurrentQuantity();
        BigDecimal divergence = request.physicalQuantity().subtract(registeredQuantity);

        InventoryCount count = InventoryCount.builder()
            .inventory(inventory)
            .batch(batch)
            .registeredQuantity(registeredQuantity)
            .physicalQuantity(request.physicalQuantity())
            .divergence(divergence)
            .note(request.note())
            .build();

        return countRepository.save(count);
    }

    @Override
    public Inventory removeCount(Integer inventoryId, Integer countId) {
        Inventory inventory = findOpenInventory(inventoryId);

        InventoryCount count = countRepository.findById(countId)
            .orElseThrow(() -> new ResourceNotFoundException("Contagem não encontrada."));
        if (!count.getInventory().getId().equals(inventoryId)) {
            throw new BusinessRuleException("Contagem não pertence a esse inventário.");
        }

        countRepository.delete(count);
        return inventory;
    }

    @Override
    public List<InventoryCount> listCounts(Integer inventoryId) {
        findById(inventoryId);
        return countRepository.findByInventoryId(inventoryId);
    }

    @Override
    @Transactional
    public Inventory close(Integer inventoryId) {
        Inventory inventory = findOpenInventory(inventoryId);

        List<InventoryCount> counts = countRepository.findByInventoryId(inventoryId);
        if (counts.isEmpty()) {
            throw new BusinessRuleException("Inventário sem contagens não pode ser fechado.");
        }

        for (InventoryCount count : counts) {
            stockBatchUseCase.adjust(count.getBatch().getId(), count.getPhysicalQuantity());
        }

        // sp_close_inventory muda o status para CLOSED e preenche closed_at
        repository.callCloseInventory(inventory.getId());

        return repository.findById(inventoryId)
            .orElseThrow(() -> new ResourceNotFoundException("Inventário não encontrado."));
    }

    @Override
    public Inventory cancel(Integer inventoryId) {
        Inventory inventory = findOpenInventory(inventoryId);

        inventory.setStatus(InventoryStatus.CANCELLED);
        inventory.setClosedAt(LocalDateTime.now());

        return repository.save(inventory);
    }

    private Inventory findOpenInventory(Integer inventoryId) {
        Inventory inventory = findById(inventoryId);
        // findById já chama accessGuard.assertAccess

        if (inventory.getStatus() != InventoryStatus.OPEN) {
            throw new BusinessRuleException("Inventário não está mais aberto.");
        }

        return inventory;
    }
}
