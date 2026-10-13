package com.inventra.api.core.service.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryServiceTest {

    private static final Integer KITCHEN_ID = 1;
    private static final Integer INVENTORY_ID = 4;
    private static final Integer BATCH_ID = 10;
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock private InventoryRepository repository;
    @Mock private InventoryCountRepository countRepository;
    @Mock private KitchenRepository kitchenRepository;
    @Mock private UserRepository userRepository;
    @Mock private StockBatchRepository stockBatchRepository;
    @Mock private StockBatchUseCase stockBatchUseCase;
    @Mock private KitchenAccessGuard accessGuard;

    @InjectMocks private InventoryService service;

    private Kitchen kitchen;
    private User user;

    @BeforeEach
    void setUp() {
        kitchen = Kitchen.builder().id(KITCHEN_ID).name("Central").active(true).build();
        user = User.builder().id(USER_ID).name("Maria").kitchen(kitchen).build();
        when(accessGuard.currentUser()).thenReturn(user);
    }

    private Inventory inventory(InventoryStatus status) {
        return Inventory.builder().id(INVENTORY_ID).kitchen(kitchen).responsible(user).status(status).build();
    }

    private void stubInventory(InventoryStatus status) {
        when(repository.findById(INVENTORY_ID)).thenReturn(Optional.of(inventory(status)));
    }

    private StockBatch batch(String quantity) {
        return StockBatch.builder().id(BATCH_ID).kitchen(kitchen).currentQuantity(new BigDecimal(quantity)).build();
    }

    // ---------- open ----------

    @Test
    void openCreatesOpenInventoryWithLoggedUserAsResponsible() {
        when(kitchenRepository.findByIdForUpdate(KITCHEN_ID)).thenReturn(Optional.of(kitchen));
        when(repository.existsByKitchenIdAndStatus(KITCHEN_ID, InventoryStatus.OPEN)).thenReturn(false);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(repository.save(any(Inventory.class))).thenAnswer(inv -> inv.getArgument(0));

        Inventory result = service.open(new OpenInventoryRequest(KITCHEN_ID, null, "Mensal"));

        assertEquals(InventoryStatus.OPEN, result.getStatus());
        assertEquals(user, result.getResponsible());
    }

    @Test
    void openRejectsWhenAnotherInventoryIsAlreadyOpen() {
        when(kitchenRepository.findByIdForUpdate(KITCHEN_ID)).thenReturn(Optional.of(kitchen));
        when(repository.existsByKitchenIdAndStatus(KITCHEN_ID, InventoryStatus.OPEN)).thenReturn(true);

        assertThrows(BusinessRuleException.class,
                () -> service.open(new OpenInventoryRequest(KITCHEN_ID, null, null)));
        verify(repository, never()).save(any());
    }

    @Test
    void openRejectsInactiveKitchen() {
        kitchen.setActive(false);
        when(kitchenRepository.findByIdForUpdate(KITCHEN_ID)).thenReturn(Optional.of(kitchen));

        assertThrows(BusinessRuleException.class,
                () -> service.open(new OpenInventoryRequest(KITCHEN_ID, null, null)));
    }

    @Test
    void openRejectsResponsibleFromAnotherKitchen() {
        UUID otherId = UUID.randomUUID();
        Kitchen otherKitchen = Kitchen.builder().id(99).active(true).build();
        when(kitchenRepository.findByIdForUpdate(KITCHEN_ID)).thenReturn(Optional.of(kitchen));
        when(userRepository.findById(otherId))
                .thenReturn(Optional.of(User.builder().id(otherId).kitchen(otherKitchen).build()));

        assertThrows(BusinessRuleException.class,
                () -> service.open(new OpenInventoryRequest(KITCHEN_ID, otherId, null)));
        verify(repository, never()).save(any());
    }

    @Test
    void openIsBlockedWithoutAccessToKitchen() {
        doThrow(new AccessDeniedException("sem acesso")).when(accessGuard).assertAccess(KITCHEN_ID);

        assertThrows(AccessDeniedException.class,
                () -> service.open(new OpenInventoryRequest(KITCHEN_ID, null, null)));
    }

    // ---------- findById ----------

    @Test
    void findByIdThrowsWhenMissing() {
        when(repository.findById(404)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.findById(404));
    }

    // ---------- registerCount ----------

    @Test
    void registerCountComputesDivergenceAgainstCurrentBalance() {
        stubInventory(InventoryStatus.OPEN);
        when(stockBatchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch("10")));
        when(countRepository.existsByInventoryIdAndBatchId(INVENTORY_ID, BATCH_ID)).thenReturn(false);
        when(countRepository.save(any(InventoryCount.class))).thenAnswer(inv -> inv.getArgument(0));

        InventoryCount result = service.registerCount(INVENTORY_ID,
                new RegisterInventoryCountRequest(BATCH_ID, new BigDecimal("8.5"), null));

        assertEquals(new BigDecimal("10"), result.getRegisteredQuantity());
        assertEquals(new BigDecimal("-1.5"), result.getDivergence());
    }

    @Test
    void registerCountRejectsBatchFromAnotherKitchen() {
        stubInventory(InventoryStatus.OPEN);
        StockBatch foreign = batch("10");
        foreign.setKitchen(Kitchen.builder().id(99).build());
        when(stockBatchRepository.findById(BATCH_ID)).thenReturn(Optional.of(foreign));

        assertThrows(BusinessRuleException.class, () -> service.registerCount(INVENTORY_ID,
                new RegisterInventoryCountRequest(BATCH_ID, BigDecimal.ONE, null)));
        verify(countRepository, never()).save(any());
    }

    @Test
    void registerCountRejectsBatchAlreadyCounted() {
        stubInventory(InventoryStatus.OPEN);
        when(stockBatchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch("10")));
        when(countRepository.existsByInventoryIdAndBatchId(INVENTORY_ID, BATCH_ID)).thenReturn(true);

        assertThrows(BusinessRuleException.class, () -> service.registerCount(INVENTORY_ID,
                new RegisterInventoryCountRequest(BATCH_ID, BigDecimal.ONE, null)));
    }

    @Test
    void registerCountRejectsClosedInventory() {
        stubInventory(InventoryStatus.CLOSED);

        assertThrows(BusinessRuleException.class, () -> service.registerCount(INVENTORY_ID,
                new RegisterInventoryCountRequest(BATCH_ID, BigDecimal.ONE, null)));
    }

    // ---------- removeCount ----------

    @Test
    void removeCountDeletesCountOfTheInventory() {
        Inventory open = inventory(InventoryStatus.OPEN);
        when(repository.findById(INVENTORY_ID)).thenReturn(Optional.of(open));
        InventoryCount count = InventoryCount.builder().id(7).inventory(open).build();
        when(countRepository.findById(7)).thenReturn(Optional.of(count));

        service.removeCount(INVENTORY_ID, 7);

        verify(countRepository).delete(count);
    }

    @Test
    void removeCountRejectsCountFromAnotherInventory() {
        stubInventory(InventoryStatus.OPEN);
        Inventory other = Inventory.builder().id(99).kitchen(kitchen).build();
        when(countRepository.findById(7)).thenReturn(Optional.of(InventoryCount.builder().id(7).inventory(other).build()));

        assertThrows(BusinessRuleException.class, () -> service.removeCount(INVENTORY_ID, 7));
        verify(countRepository, never()).delete(any());
    }

    // ---------- close ----------

    @Test
    void closeAppliesDivergenceOnCurrentBalanceAndCallsProcedure() {
        stubInventory(InventoryStatus.OPEN);
        // contado 8 quando o sistema tinha 10 (divergência -2); depois do contado, o saldo caiu para 6
        InventoryCount count = InventoryCount.builder().id(1).batch(batch("6"))
                .registeredQuantity(new BigDecimal("10")).physicalQuantity(new BigDecimal("8")).build();
        when(countRepository.findByInventoryId(INVENTORY_ID)).thenReturn(List.of(count));
        when(stockBatchRepository.findByIdForUpdate(BATCH_ID)).thenReturn(Optional.of(batch("6")));

        Inventory result = service.close(INVENTORY_ID);

        verify(stockBatchUseCase).adjust(BATCH_ID, new BigDecimal("4"));
        verify(repository).callCloseInventory(INVENTORY_ID);
        assertNotNull(result);
    }

    @Test
    void closeNeverAdjustsBelowZero() {
        stubInventory(InventoryStatus.OPEN);
        InventoryCount count = InventoryCount.builder().id(1).batch(batch("1"))
                .registeredQuantity(new BigDecimal("10")).physicalQuantity(new BigDecimal("0")).build();
        when(countRepository.findByInventoryId(INVENTORY_ID)).thenReturn(List.of(count));
        when(stockBatchRepository.findByIdForUpdate(BATCH_ID)).thenReturn(Optional.of(batch("1")));

        service.close(INVENTORY_ID);

        verify(stockBatchUseCase).adjust(BATCH_ID, BigDecimal.ZERO);
    }

    @Test
    void closeRejectsInventoryWithoutCounts() {
        stubInventory(InventoryStatus.OPEN);
        when(countRepository.findByInventoryId(INVENTORY_ID)).thenReturn(List.of());

        assertThrows(BusinessRuleException.class, () -> service.close(INVENTORY_ID));
        verify(repository, never()).callCloseInventory(any());
    }

    @Test
    void closeRejectsAlreadyClosedInventory() {
        stubInventory(InventoryStatus.CLOSED);

        assertThrows(BusinessRuleException.class, () -> service.close(INVENTORY_ID));
        verify(repository, never()).callCloseInventory(any());
    }

    // ---------- cancel ----------

    @Test
    void cancelMarksInventoryCancelledWithClosingTime() {
        stubInventory(InventoryStatus.OPEN);
        when(repository.save(any(Inventory.class))).thenAnswer(inv -> inv.getArgument(0));

        Inventory result = service.cancel(INVENTORY_ID);

        assertEquals(InventoryStatus.CANCELLED, result.getStatus());
        assertNotNull(result.getClosedAt());
    }

    @Test
    void cancelRejectsNonOpenInventory() {
        stubInventory(InventoryStatus.CANCELLED);

        assertThrows(BusinessRuleException.class, () -> service.cancel(INVENTORY_ID));
        verify(repository, never()).save(any());
    }
}
