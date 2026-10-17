package com.inventra.api.core.service.stockbatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

import com.inventra.api.core.domain.category.Category;
import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.product.Product;
import com.inventra.api.core.domain.product.ProductKitchenParameter;
import com.inventra.api.core.domain.stock.StockBatch;
import com.inventra.api.core.domain.stock.StockMovement;
import com.inventra.api.core.domain.stock.enums.StockMovementReason;
import com.inventra.api.core.domain.stock.enums.StockMovementType;
import com.inventra.api.core.service.stockbatch.model.request.ConsumeProductRequest;
import com.inventra.api.core.service.stockbatch.model.response.BatchConsumptionResponse;
import org.mockito.ArgumentCaptor;
import com.inventra.api.core.domain.stock.enums.StockBatchStatus;
import com.inventra.api.core.domain.unit.Unit;
import com.inventra.api.core.service.stockbatch.model.request.RegisterStockEntryRequest;
import com.inventra.api.infrastructure.exception.BusinessRuleException;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.KitchenRepository;
import com.inventra.api.infrastructure.repository.ProductKitchenParameterRepository;
import com.inventra.api.infrastructure.repository.ProductRepository;
import com.inventra.api.infrastructure.repository.StockBatchRepository;
import com.inventra.api.infrastructure.repository.StockMovementRepository;
import com.inventra.api.core.domain.user.User;
import java.util.UUID;
import com.inventra.api.infrastructure.repository.SupplierRepository;
import com.inventra.api.infrastructure.security.KitchenAccessGuard;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockBatchServiceTest {

    private static final Integer KITCHEN_ID = 1;
    private static final Integer BATCH_ID = 10;
    private static final Integer REQUISITION_ID = 77;
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock private StockBatchRepository repository;
    @Mock private ProductRepository productRepository;
    @Mock private KitchenRepository kitchenRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private ProductKitchenParameterRepository productKitchenParameterRepository;
    @Mock private StockMovementRepository movementRepository;
    @Mock private KitchenAccessGuard accessGuard;
    @Mock private ApplicationEventPublisher events;

    @InjectMocks private StockBatchService service;

    private Kitchen kitchen;
    private Product product;

    @BeforeEach
    void setUp() {
        kitchen = Kitchen.builder().id(KITCHEN_ID).name("Central").active(true).build();
        product = Product.builder().id(3).name("Arroz").active(true).build();
        when(accessGuard.currentUser()).thenReturn(User.builder().id(USER_ID).name("Maria").build());
    }

    private StockBatch batch(String quantity, StockBatchStatus status, LocalDate expiration) {
        return StockBatch.builder().id(BATCH_ID).product(product).kitchen(kitchen)
                .currentQuantity(new BigDecimal(quantity)).initialQuantity(new BigDecimal(quantity))
                .status(status).expirationDate(expiration).build();
    }

    private RegisterStockEntryRequest entry(LocalDate entryDate, LocalDate expiration) {
        return new RegisterStockEntryRequest(3, KITCHEN_ID, null, "L1", null, new BigDecimal("20"),
                entryDate, expiration, new BigDecimal("4.50"));
    }

    // ---------- registerEntry ----------

    @Test
    void registerEntryCreatesActiveBatchWithFullBalance() {
        when(productRepository.findById(3)).thenReturn(Optional.of(product));
        when(kitchenRepository.findById(KITCHEN_ID)).thenReturn(Optional.of(kitchen));
        when(repository.save(any(StockBatch.class))).thenAnswer(inv -> inv.getArgument(0));

        StockBatch result = service.registerEntry(entry(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 1)));

        assertEquals(StockBatchStatus.ACTIVE, result.getStatus());
        assertEquals(new BigDecimal("20"), result.getCurrentQuantity());
        assertEquals(result.getInitialQuantity(), result.getCurrentQuantity());
        verify(accessGuard).assertAccess(KITCHEN_ID);
    }

    @Test
    void registerEntryRejectsExpirationBeforeEntryDate() {
        assertThrows(IllegalArgumentException.class,
                () -> service.registerEntry(entry(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 1))));
        verify(repository, never()).save(any());
    }

    @Test
    void registerEntryRejectsInactiveProduct() {
        product.setActive(false);
        when(productRepository.findById(3)).thenReturn(Optional.of(product));

        assertThrows(BusinessRuleException.class, () -> service.registerEntry(entry(LocalDate.now(), null)));
        verify(repository, never()).save(any());
    }

    @Test
    void registerEntryRejectsInactiveKitchen() {
        kitchen.setActive(false);
        when(productRepository.findById(3)).thenReturn(Optional.of(product));
        when(kitchenRepository.findById(KITCHEN_ID)).thenReturn(Optional.of(kitchen));

        assertThrows(BusinessRuleException.class, () -> service.registerEntry(entry(LocalDate.now(), null)));
    }

    @Test
    void registerEntryFailsForUnknownProduct() {
        when(productRepository.findById(3)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.registerEntry(entry(LocalDate.now(), null)));
    }

    // ---------- consume ----------

    @Test
    void consumeCallsWriteOffProcedure() {
        when(repository.findById(BATCH_ID)).thenReturn(Optional.of(batch("10", StockBatchStatus.ACTIVE, null)));

        service.consume(BATCH_ID, new BigDecimal("4"), null);

        verify(repository).callWriteOffStock(BATCH_ID, new BigDecimal("4"), USER_ID, "USAGE");
    }

    @Test
    void consumeRejectsQuantityAboveBalance() {
        when(repository.findById(BATCH_ID)).thenReturn(Optional.of(batch("3", StockBatchStatus.ACTIVE, null)));

        assertThrows(BusinessRuleException.class, () -> service.consume(BATCH_ID, new BigDecimal("4"), null));
        verify(repository, never()).callWriteOffStock(any(), any(), any(), any());
    }

    @Test
    void consumeRejectsExpiredBatch() {
        when(repository.findById(BATCH_ID))
                .thenReturn(Optional.of(batch("10", StockBatchStatus.ACTIVE, LocalDate.now().minusDays(1))));

        assertThrows(BusinessRuleException.class, () -> service.consume(BATCH_ID, BigDecimal.ONE, null));
        verify(repository, never()).callWriteOffStock(any(), any(), any(), any());
    }

    @Test
    void consumeRejectsNonActiveBatch() {
        when(repository.findById(BATCH_ID)).thenReturn(Optional.of(batch("10", StockBatchStatus.WRITTEN_OFF, null)));

        assertThrows(BusinessRuleException.class, () -> service.consume(BATCH_ID, BigDecimal.ONE, null));
    }

    @Test
    void consumeFailsForUnknownBatch() {
        when(repository.findById(BATCH_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.consume(BATCH_ID, BigDecimal.ONE, null));
    }

    // ---------- restock ----------

    @Test
    void restockCallsRegisterEntryProcedure() {
        when(repository.findById(BATCH_ID)).thenReturn(Optional.of(batch("0", StockBatchStatus.WRITTEN_OFF, null)));

        service.restock(BATCH_ID, new BigDecimal("5"));

        verify(repository).callRegisterStockEntry(BATCH_ID, new BigDecimal("5"), USER_ID);
    }

    @Test
    void restockRejectsInactiveKitchen() {
        kitchen.setActive(false);
        when(repository.findById(BATCH_ID)).thenReturn(Optional.of(batch("1", StockBatchStatus.ACTIVE, null)));

        assertThrows(BusinessRuleException.class, () -> service.restock(BATCH_ID, BigDecimal.ONE));
        verify(repository, never()).callRegisterStockEntry(any(), any(), any());
    }

    @Test
    void consumeRecordsTheGivenReasonAndAllowsDiscardingAnExpiredBatch() {
        when(repository.findById(BATCH_ID))
                .thenReturn(Optional.of(batch("10", StockBatchStatus.ACTIVE, LocalDate.now().minusDays(1))));

        service.consume(BATCH_ID, new BigDecimal("4"), StockMovementReason.EXPIRED);

        verify(repository).callWriteOffStock(BATCH_ID, new BigDecimal("4"), USER_ID, "EXPIRED");
    }

    @Test
    void consumeRejectsTheReasonReservedForRequisitions() {
        assertThrows(IllegalArgumentException.class,
                () -> service.consume(BATCH_ID, BigDecimal.ONE, StockMovementReason.REQUISITION));
        verify(repository, never()).callWriteOffStock(any(), any(), any(), any());
    }

    // ---------- consumeProduct (scan) ----------

    @Test
    void consumeProductTakesFromBatchesThatExpireFirstAndReturnsTheAffectedOnes() {
        StockBatch first = batch("3", StockBatchStatus.ACTIVE, LocalDate.now().plusDays(2));
        first.setId(11);
        StockBatch second = batch("10", StockBatchStatus.ACTIVE, LocalDate.now().plusDays(9));
        second.setId(12);
        when(kitchenRepository.findById(KITCHEN_ID)).thenReturn(Optional.of(kitchen));
        when(productRepository.existsById(3)).thenReturn(true);
        when(repository.findUsableForConsumption(eq(KITCHEN_ID), eq(3), any(LocalDate.class)))
                .thenReturn(List.of(first, second));

        List<BatchConsumptionResponse> result = service.consumeProduct(
                new ConsumeProductRequest(KITCHEN_ID, 3, new BigDecimal("5"), StockMovementReason.LOSS));

        verify(repository).callWriteOffStock(11, new BigDecimal("3"), USER_ID, "LOSS");
        verify(repository).callWriteOffStock(12, new BigDecimal("2"), USER_ID, "LOSS");
        assertEquals(2, result.size());
        assertEquals(0, result.get(0).remainingQuantity().signum());
        assertEquals(StockBatchStatus.WRITTEN_OFF, result.get(0).status());
        assertEquals(new BigDecimal("8"), result.get(1).remainingQuantity());
        assertEquals(StockBatchStatus.ACTIVE, result.get(1).status());
    }

    @Test
    void consumeProductFailsWhenStockIsInsufficient() {
        when(kitchenRepository.findById(KITCHEN_ID)).thenReturn(Optional.of(kitchen));
        when(productRepository.existsById(3)).thenReturn(true);
        when(repository.findUsableForConsumption(eq(KITCHEN_ID), eq(3), any(LocalDate.class)))
                .thenReturn(List.of(batch("2", StockBatchStatus.ACTIVE, null)));

        assertThrows(BusinessRuleException.class, () -> service.consumeProduct(
                new ConsumeProductRequest(KITCHEN_ID, 3, new BigDecimal("5"), null)));
    }

    @Test
    void consumeProductFailsForUnknownProduct() {
        when(kitchenRepository.findById(KITCHEN_ID)).thenReturn(Optional.of(kitchen));
        when(productRepository.existsById(3)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> service.consumeProduct(
                new ConsumeProductRequest(KITCHEN_ID, 3, BigDecimal.ONE, null)));
    }

    // ---------- movimentações gravadas pela API ----------

    @Test
    void registerEntryRecordsTheEntryMovement() {
        when(productRepository.findById(3)).thenReturn(Optional.of(product));
        when(kitchenRepository.findById(KITCHEN_ID)).thenReturn(Optional.of(kitchen));
        when(repository.save(any(StockBatch.class))).thenAnswer(inv -> inv.getArgument(0));

        service.registerEntry(entry(LocalDate.of(2026, 10, 1), null));

        ArgumentCaptor<StockMovement> movement = ArgumentCaptor.forClass(StockMovement.class);
        verify(movementRepository).save(movement.capture());
        assertEquals(StockMovementType.ENTRY, movement.getValue().getType());
        assertEquals(new BigDecimal("20"), movement.getValue().getQuantity());
        assertEquals(new BigDecimal("20"), movement.getValue().getBalanceAfter());
        assertEquals(USER_ID, movement.getValue().getUser().getId());
    }

    @Test
    void adjustRecordsTheSignedDifference() {
        when(repository.findByIdForUpdate(BATCH_ID)).thenReturn(Optional.of(batch("5", StockBatchStatus.ACTIVE, null)));
        when(repository.save(any(StockBatch.class))).thenAnswer(inv -> inv.getArgument(0));

        service.adjust(BATCH_ID, new BigDecimal("3"));

        ArgumentCaptor<StockMovement> movement = ArgumentCaptor.forClass(StockMovement.class);
        verify(movementRepository).save(movement.capture());
        assertEquals(StockMovementType.ADJUSTMENT, movement.getValue().getType());
        assertEquals(new BigDecimal("-2"), movement.getValue().getQuantity());
        assertEquals(new BigDecimal("3"), movement.getValue().getBalanceAfter());
    }

    @Test
    void adjustToTheSameBalanceRecordsNoMovement() {
        when(repository.findByIdForUpdate(BATCH_ID)).thenReturn(Optional.of(batch("5", StockBatchStatus.ACTIVE, null)));
        when(repository.save(any(StockBatch.class))).thenAnswer(inv -> inv.getArgument(0));

        service.adjust(BATCH_ID, new BigDecimal("5"));

        verify(movementRepository, never()).save(any());
    }

    // ---------- consumeForRequisition (FIFO) ----------

    @Test
    void consumeForProductTakesFromBatchesInOrderUntilSatisfied() {
        StockBatch first = batch("3", StockBatchStatus.ACTIVE, null);
        first.setId(11);
        StockBatch second = batch("10", StockBatchStatus.ACTIVE, null);
        second.setId(12);
        when(repository.findUsableForConsumption(eq(KITCHEN_ID), eq(3), any(LocalDate.class)))
                .thenReturn(List.of(first, second));

        service.consumeForRequisition(KITCHEN_ID, 3, new BigDecimal("5"), REQUISITION_ID);

        verify(repository).callWriteOffStockForRequisition(11, new BigDecimal("3"), USER_ID, "REQUISITION", REQUISITION_ID);
        verify(repository).callWriteOffStockForRequisition(12, new BigDecimal("2"), USER_ID, "REQUISITION", REQUISITION_ID);
    }

    @Test
    void consumeForProductFailsWhenStockIsInsufficient() {
        StockBatch only = batch("2", StockBatchStatus.ACTIVE, null);
        when(repository.findUsableForConsumption(eq(KITCHEN_ID), eq(3), any(LocalDate.class)))
                .thenReturn(List.of(only));

        assertThrows(BusinessRuleException.class,
                () -> service.consumeForRequisition(KITCHEN_ID, 3, new BigDecimal("5"), REQUISITION_ID));
    }

    // ---------- adjust ----------

    @Test
    void adjustToZeroWritesOffTheBatch() {
        StockBatch existing = batch("5", StockBatchStatus.ACTIVE, null);
        when(repository.findByIdForUpdate(BATCH_ID)).thenReturn(Optional.of(existing));
        when(repository.save(any(StockBatch.class))).thenAnswer(inv -> inv.getArgument(0));

        StockBatch result = service.adjust(BATCH_ID, BigDecimal.ZERO);

        assertEquals(StockBatchStatus.WRITTEN_OFF, result.getStatus());
    }

    @Test
    void adjustPositiveReactivatesWrittenOffBatch() {
        StockBatch existing = batch("0", StockBatchStatus.WRITTEN_OFF, null);
        when(repository.findByIdForUpdate(BATCH_ID)).thenReturn(Optional.of(existing));
        when(repository.save(any(StockBatch.class))).thenAnswer(inv -> inv.getArgument(0));

        StockBatch result = service.adjust(BATCH_ID, new BigDecimal("7"));

        assertEquals(StockBatchStatus.ACTIVE, result.getStatus());
        assertEquals(new BigDecimal("7"), result.getCurrentQuantity());
    }

    @Test
    void adjustFailsForUnknownBatch() {
        when(repository.findByIdForUpdate(BATCH_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.adjust(BATCH_ID, BigDecimal.ONE));
    }

    // ---------- consultas ----------

    @Test
    void findExpiringSoonRejectsNegativeDays() {
        assertThrows(IllegalArgumentException.class, () -> service.findExpiringSoon(KITCHEN_ID, -1));
    }

    @Test
    void findExpiringSoonQueriesActiveBatchesInTheWindow() {
        List<StockBatch> found = List.of(batch("1", StockBatchStatus.ACTIVE, LocalDate.now().plusDays(2)));
        when(repository.findByKitchenIdAndStatusAndExpirationDateBetween(
                eq(KITCHEN_ID), eq(StockBatchStatus.ACTIVE), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(found);

        assertSame(found, service.findExpiringSoon(KITCHEN_ID, 7));
    }

    @Test
    void findLowStockIsEmptyForUserWithoutKitchen() {
        when(accessGuard.currentKitchenId()).thenReturn(null);

        assertEquals(List.of(), service.findLowStock());
        verify(productKitchenParameterRepository, never()).findBelowMinimum(any(), any());
    }

    @Test
    void findLowStockIsEmptyWhenNothingIsBelowMinimum() {
        when(accessGuard.currentKitchenId()).thenReturn(KITCHEN_ID);
        when(productKitchenParameterRepository.findBelowMinimum(eq(KITCHEN_ID), any(LocalDate.class)))
                .thenReturn(List.of());

        assertEquals(List.of(), service.findLowStock());
    }

    @Test
    void listByProductIsEmptyForUserWithoutKitchen() {
        when(accessGuard.currentKitchenId()).thenReturn(null);

        assertEquals(List.of(), service.listByProduct(3));
    }

    // ---------- listByKitchenAndProduct ----------

    @Test
    void listByKitchenAndProductFiltersByBothAfterCheckingAccess() {
        List<StockBatch> batches = List.of(batch("5", StockBatchStatus.ACTIVE, null));
        when(repository.findByKitchenIdAndProductId(KITCHEN_ID, 3)).thenReturn(batches);

        assertSame(batches, service.listByKitchenAndProduct(KITCHEN_ID, 3));
        verify(accessGuard).assertAccess(KITCHEN_ID);
    }

    @Test
    void listByKitchenAndProductDeniesAnotherKitchen() {
        doThrow(new AccessDeniedException("sem acesso")).when(accessGuard).assertAccess(99);

        assertThrows(AccessDeniedException.class, () -> service.listByKitchenAndProduct(99, 3));
        verify(repository, never()).findByKitchenIdAndProductId(any(), any());
    }

    // ---------- summarizeKitchenStock ----------

    private static Product stockProduct(int id, String name) {
        return Product.builder().id(id).name(name).brand("Marca").active(true)
                .category(Category.builder().id(2).name("Laticínios").build())
                .unit(Unit.builder().id(3).symbol("L").build()).build();
    }

    private static ProductKitchenParameter parameter(Product product, String minStock) {
        return ProductKitchenParameter.builder().product(product).minStock(new BigDecimal(minStock)).build();
    }

    @Test
    void summarizeKitchenStockCombinesProductsParametersAndUsableBalance() {
        Product milk = stockProduct(5, "Leite");
        Product rice = stockProduct(6, "Arroz");
        Product beans = stockProduct(7, "Feijão");
        LocalDate expiration = LocalDate.now().plusDays(5);
        when(productRepository.findKitchenProducts(KITCHEN_ID, "", null)).thenReturn(List.of(
                new Object[] {rice, null},                      // só tem lote, sem parâmetro
                new Object[] {milk, parameter(milk, "10")},     // saldo 18 >= mínimo 10
                new Object[] {beans, parameter(beans, "4")}));  // sem lote utilizável: aparece zerado
        when(repository.summarizeUsableByProduct(eq(KITCHEN_ID), any(LocalDate.class))).thenReturn(List.of(
                new Object[] {5, new BigDecimal("18"), 2L, expiration},
                new Object[] {6, new BigDecimal("3"), 1L, null}));

        var result = service.summarizeKitchenStock(KITCHEN_ID, null, null);

        verify(accessGuard).assertAccess(KITCHEN_ID);
        assertEquals(3, result.size());
        var rizItem = result.get(0);
        assertEquals("Arroz", rizItem.product().name());
        assertEquals(new BigDecimal("3"), rizItem.totalQuantity());
        assertEquals(1, rizItem.activeBatches());
        assertNull(rizItem.nextExpirationDate());
        assertNull(rizItem.minStock());
        assertFalse(rizItem.lowStock());
        var milkItem = result.get(1);
        assertEquals(new BigDecimal("18"), milkItem.totalQuantity());
        assertEquals(2, milkItem.activeBatches());
        assertEquals(expiration, milkItem.nextExpirationDate());
        assertEquals(new BigDecimal("10"), milkItem.minStock());
        assertFalse(milkItem.lowStock());
        assertEquals("Laticínios", milkItem.category().name());
        assertEquals("L", milkItem.unit().symbol());
        var beansItem = result.get(2);
        assertEquals(BigDecimal.ZERO, beansItem.totalQuantity());
        assertEquals(0, beansItem.activeBatches());
        assertNull(beansItem.nextExpirationDate());
        assertTrue(beansItem.lowStock());
    }

    @Test
    void summarizeKitchenStockIsNotLowStockWhenBalanceEqualsMinimum() {
        Product milk = stockProduct(5, "Leite");
        when(productRepository.findKitchenProducts(KITCHEN_ID, "", null))
                .thenReturn(List.<Object[]>of(new Object[] {milk, parameter(milk, "10")}));
        when(repository.summarizeUsableByProduct(eq(KITCHEN_ID), any(LocalDate.class))).thenReturn(List.<Object[]>of(
                new Object[] {5, new BigDecimal("10"), 1L, null}));

        assertFalse(service.summarizeKitchenStock(KITCHEN_ID, null, null).get(0).lowStock());
    }

    @Test
    void summarizeKitchenStockPassesTrimmedNameAndCategoryFilters() {
        when(productRepository.findKitchenProducts(KITCHEN_ID, "lei", 2)).thenReturn(List.of());

        assertEquals(List.of(), service.summarizeKitchenStock(KITCHEN_ID, 2, "  lei "));
        verify(productRepository).findKitchenProducts(KITCHEN_ID, "lei", 2);
    }

    @Test
    void summarizeKitchenStockHandlesProductWithoutCategory() {
        Product product = Product.builder().id(8).name("Sal").active(true)
                .unit(Unit.builder().id(1).symbol("kg").build()).build();
        when(productRepository.findKitchenProducts(KITCHEN_ID, "", null))
                .thenReturn(List.<Object[]>of(new Object[] {product, null}));

        assertNull(service.summarizeKitchenStock(KITCHEN_ID, null, null).get(0).category());
    }

    @Test
    void summarizeKitchenStockDeniesAnotherKitchen() {
        doThrow(new AccessDeniedException("sem acesso")).when(accessGuard).assertAccess(99);

        assertThrows(AccessDeniedException.class, () -> service.summarizeKitchenStock(99, null, null));
        verify(productRepository, never()).findKitchenProducts(any(), any(), any());
    }
}
