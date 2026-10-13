package com.inventra.api.core.service.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import com.inventra.api.core.domain.alert.Alert;
import com.inventra.api.core.domain.alert.enums.AlertSeverity;
import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.product.Product;
import com.inventra.api.core.domain.stock.StockBatch;
import com.inventra.api.core.service.alert.model.request.CreateAlertRequest;
import com.inventra.api.infrastructure.exception.BusinessRuleException;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.AlertReadRepository;
import com.inventra.api.infrastructure.repository.AlertRepository;
import com.inventra.api.infrastructure.client.openfoodfacts.OpenFoodFactsClient;
import com.inventra.api.infrastructure.client.openfoodfacts.model.OpenFoodFactsProduct;
import com.inventra.api.core.domain.alert.AlertRead;
import com.inventra.api.core.domain.alert.AlertReadId;
import com.inventra.api.core.domain.alert.AlertType;
import com.inventra.api.core.domain.profile.Profile;
import com.inventra.api.core.domain.user.User;
import java.util.UUID;
import com.inventra.api.infrastructure.repository.KitchenRepository;
import com.inventra.api.infrastructure.repository.ProductRepository;
import com.inventra.api.infrastructure.repository.StockBatchRepository;
import com.inventra.api.infrastructure.security.KitchenAccessGuard;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlertServiceTest {

    private static final Integer KITCHEN_ID = 1;

    @Mock private AlertRepository repository;
    @Mock private KitchenRepository kitchenRepository;
    @Mock private ProductRepository productRepository;
    @Mock private StockBatchRepository stockBatchRepository;
    @Mock private KitchenAccessGuard accessGuard;
    @Mock private AlertReadRepository readRepository;
    @Mock private AlertPublisher publisher;
    @Mock private OpenFoodFactsClient openFoodFactsClient;

    @InjectMocks private AlertService service;

    private Kitchen kitchen;
    private Product product;
    private User user;

    @BeforeEach
    void setUp() {
        kitchen = Kitchen.builder().id(KITCHEN_ID).name("Central").active(true).build();
        product = Product.builder().id(3).name("Arroz").active(true).build();
        when(kitchenRepository.findById(KITCHEN_ID)).thenReturn(Optional.of(kitchen));
        when(repository.save(any(Alert.class))).thenAnswer(inv -> inv.getArgument(0));
        user = User.builder().id(UUID.randomUUID()).name("Ana")
                .profile(Profile.builder().id(2).accessType("estoquista").build()).build();
        when(accessGuard.currentUser()).thenReturn(user);
    }

    private CreateAlertRequest request(Integer productId, Integer batchId) {
        return new CreateAlertRequest("LOW_STOCK", AlertSeverity.HIGH, batchId, productId, KITCHEN_ID, "Estoque baixo");
    }

    private Alert alert() {
        return Alert.builder().id(5).type("LOW_STOCK").severity(AlertSeverity.HIGH).kitchen(kitchen)
                .message("Estoque baixo").read(false).build();
    }

    // ---------- create ----------

    @Test
    void createSavesUnreadAlert() {
        Alert result = service.create(request(null, null));

        assertFalse(result.getRead());
        assertEquals(AlertSeverity.HIGH, result.getSeverity());
        assertSame(kitchen, result.getKitchen());
        verify(accessGuard).assertAccess(KITCHEN_ID);
    }

    @Test
    void createLinksProductAndBatchOfTheSameKitchen() {
        StockBatch batch = StockBatch.builder().id(10).kitchen(kitchen).product(product).build();
        when(productRepository.findById(3)).thenReturn(Optional.of(product));
        when(stockBatchRepository.findById(10)).thenReturn(Optional.of(batch));

        Alert result = service.create(request(3, 10));

        assertSame(product, result.getProduct());
        assertSame(batch, result.getBatch());
    }

    @Test
    void createRejectsBatchFromAnotherKitchen() {
        StockBatch foreign = StockBatch.builder().id(10).kitchen(Kitchen.builder().id(99).build()).product(product).build();
        when(stockBatchRepository.findById(10)).thenReturn(Optional.of(foreign));

        assertThrows(BusinessRuleException.class, () -> service.create(request(null, 10)));
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsBatchThatBelongsToAnotherProduct() {
        Product other = Product.builder().id(4).build();
        StockBatch batch = StockBatch.builder().id(10).kitchen(kitchen).product(other).build();
        when(productRepository.findById(3)).thenReturn(Optional.of(product));
        when(stockBatchRepository.findById(10)).thenReturn(Optional.of(batch));

        assertThrows(BusinessRuleException.class, () -> service.create(request(3, 10)));
    }

    @Test
    void createFailsForUnknownProductBatchOrKitchen() {
        when(productRepository.findById(3)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.create(request(3, null)));

        when(stockBatchRepository.findById(10)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.create(request(null, 10)));

        when(kitchenRepository.findById(KITCHEN_ID)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.create(request(null, null)));
    }

    @Test
    void createIsBlockedWithoutAccessToKitchen() {
        doThrow(new AccessDeniedException("sem acesso")).when(accessGuard).assertAccess(KITCHEN_ID);

        assertThrows(AccessDeniedException.class, () -> service.create(request(null, null)));
        verify(repository, never()).save(any());
    }

    // ---------- leitura ----------

    @Test
    void findByIdThrowsWhenMissing() {
        when(repository.findById(404)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.findById(404));
    }

    @Test
    void findByIdChecksAccessToTheAlertKitchen() {
        when(repository.findById(5)).thenReturn(Optional.of(alert()));
        doThrow(new AccessDeniedException("sem acesso")).when(accessGuard).assertAccess(KITCHEN_ID);

        assertThrows(AccessDeniedException.class, () -> service.findById(5));
    }

    @Test
    void findByIdHidesAlertAddressedToSomeoneElse() {
        Alert personal = alert();
        personal.setRecipient(User.builder().id(UUID.randomUUID()).build());
        when(repository.findById(5)).thenReturn(Optional.of(personal));

        assertThrows(AccessDeniedException.class, () -> service.findById(5));
    }

    @Test
    void listByKitchenMarksAsReadOnlyWhatTheCallerRead() {
        Alert readByMe = alert();
        Alert unread = alert();
        unread.setId(6);
        Alert resolved = alert();
        resolved.setId(7);
        resolved.setRead(true);
        when(repository.findVisible(KITCHEN_ID, user.getId(), 2, true)).thenReturn(List.of(readByMe, unread, resolved));
        when(readRepository.findReadAlertIds(user.getId(), List.of(5, 6, 7))).thenReturn(List.of(5));

        List<AlertView> result = service.listByKitchen(KITCHEN_ID);

        assertEquals(List.of(true, false, true), result.stream().map(AlertView::read).toList());
        verify(accessGuard).assertAccess(KITCHEN_ID);
    }

    @Test
    void buyerOnlySeesAlertsAddressedToThem() {
        user.setProfile(Profile.builder().id(3).accessType("comprador").build());
        when(repository.findUnreadVisible(KITCHEN_ID, user.getId(), 3, false)).thenReturn(List.of(alert()));

        assertEquals(1, service.listUnreadByKitchen(KITCHEN_ID).size());

        // alerta de estoque, sem destinatário: não é para o comprador
        when(repository.findById(5)).thenReturn(Optional.of(alert()));
        assertThrows(AccessDeniedException.class, () -> service.findById(5));
    }

    // ---------- markAsRead / delete ----------

    @Test
    void markAsReadRecordsTheReadingOnlyForTheCaller() {
        Alert existing = alert();
        when(repository.findById(5)).thenReturn(Optional.of(existing));

        AlertView result = service.markAsRead(5);

        assertTrue(result.read());
        // o alerta em si não muda: os outros continuam vendo como não lido
        assertFalse(existing.getRead());
        ArgumentCaptor<AlertRead> read = ArgumentCaptor.forClass(AlertRead.class);
        verify(readRepository).save(read.capture());
        assertEquals(new AlertReadId(5, user.getId()), read.getValue().getId());
    }

    @Test
    void markAsReadTwiceDoesNotDuplicateTheReading() {
        when(repository.findById(5)).thenReturn(Optional.of(alert()));
        when(readRepository.existsById(new AlertReadId(5, user.getId()))).thenReturn(true);

        service.markAsRead(5);

        verify(readRepository, never()).save(any());
    }

    @Test
    void markAllAsReadRecordsEveryUnreadAlertOfTheCaller() {
        Alert second = alert();
        second.setId(6);
        when(repository.findUnreadVisible(KITCHEN_ID, user.getId(), 2, true)).thenReturn(List.of(alert(), second));

        service.markAllAsRead(KITCHEN_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AlertRead>> reads = ArgumentCaptor.forClass(List.class);
        verify(readRepository).saveAll(reads.capture());
        assertEquals(List.of(new AlertReadId(5, user.getId()), new AlertReadId(6, user.getId())),
                reads.getValue().stream().map(AlertRead::getId).toList());
    }

    // ---------- produto não cadastrado ----------

    @Test
    void reportUnregisteredProductNotifiesSupervisorsWithTheProductName() {
        when(repository.findFirstOpenByOrigin(KITCHEN_ID, AlertType.UNREGISTERED_PRODUCT, "789")).thenReturn(Optional.empty());
        when(openFoodFactsClient.findByBarcode("789"))
                .thenReturn(Optional.of(new OpenFoodFactsProduct("Leite Integral 1L", "Italac", null, "1 L")));
        when(publisher.notifyProfile(any(), any(), any(), any(), any(), any())).thenReturn(alert());

        var report = service.reportUnregisteredProduct(KITCHEN_ID, "789");

        assertTrue(report.created());
        verify(publisher).notifyProfile(kitchen, "supervisor", AlertType.UNREGISTERED_PRODUCT, AlertSeverity.MEDIUM,
                "Produto não cadastrado: Leite Integral 1L (789)", "789");
    }

    @Test
    void reportUnregisteredProductFallsBackToTheBarcodeWhenTheLookupFails() {
        when(repository.findFirstOpenByOrigin(KITCHEN_ID, AlertType.UNREGISTERED_PRODUCT, "789")).thenReturn(Optional.empty());
        when(openFoodFactsClient.findByBarcode("789")).thenThrow(new IllegalStateException("fora do ar"));
        when(publisher.notifyProfile(any(), any(), any(), any(), any(), any())).thenReturn(alert());

        service.reportUnregisteredProduct(KITCHEN_ID, "789");

        verify(publisher).notifyProfile(kitchen, "supervisor", AlertType.UNREGISTERED_PRODUCT, AlertSeverity.MEDIUM,
                "Produto não cadastrado: 789", "789");
    }

    @Test
    void reportUnregisteredProductReturnsTheOpenAlertInsteadOfDuplicating() {
        Alert open = alert();
        when(repository.findFirstOpenByOrigin(KITCHEN_ID, AlertType.UNREGISTERED_PRODUCT, "789")).thenReturn(Optional.of(open));

        var report = service.reportUnregisteredProduct(KITCHEN_ID, "789");

        assertFalse(report.created());
        assertSame(open, report.alert());
        verify(publisher, never()).notifyProfile(any(), any(), any(), any(), any(), any());
    }

    @Test
    void reportUnregisteredProductRejectsBarcodeAlreadyInTheCatalog() {
        when(productRepository.existsByBarcode("789")).thenReturn(true);

        assertThrows(BusinessRuleException.class, () -> service.reportUnregisteredProduct(KITCHEN_ID, "789"));
    }

    @Test
    void deleteRemovesAccessibleAlert() {
        Alert existing = alert();
        when(repository.findById(5)).thenReturn(Optional.of(existing));

        service.delete(5);

        verify(repository).delete(existing);
    }

    @Test
    void deleteIsBlockedWithoutAccess() {
        when(repository.findById(5)).thenReturn(Optional.of(alert()));
        doThrow(new AccessDeniedException("sem acesso")).when(accessGuard).assertAccess(KITCHEN_ID);

        assertThrows(AccessDeniedException.class, () -> service.delete(5));
        verify(repository, never()).delete(any());
    }
}
