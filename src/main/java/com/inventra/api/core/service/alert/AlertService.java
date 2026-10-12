package com.inventra.api.core.service.alert;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inventra.api.core.domain.alert.Alert;
import com.inventra.api.core.domain.alert.AlertRead;
import com.inventra.api.core.domain.alert.AlertReadId;
import com.inventra.api.core.domain.alert.AlertType;
import com.inventra.api.core.domain.alert.enums.AlertSeverity;
import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.product.Product;
import com.inventra.api.core.domain.stock.StockBatch;
import com.inventra.api.core.domain.user.User;
import com.inventra.api.core.service.alert.model.request.CreateAlertRequest;
import com.inventra.api.infrastructure.client.openfoodfacts.OpenFoodFactsClient;
import com.inventra.api.infrastructure.client.openfoodfacts.model.OpenFoodFactsProduct;
import com.inventra.api.infrastructure.exception.BusinessRuleException;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.AlertReadRepository;
import com.inventra.api.infrastructure.repository.AlertRepository;
import com.inventra.api.infrastructure.repository.KitchenRepository;
import com.inventra.api.infrastructure.repository.ProductRepository;
import com.inventra.api.infrastructure.repository.StockBatchRepository;
import com.inventra.api.infrastructure.security.KitchenAccessGuard;
import com.inventra.api.infrastructure.security.Roles;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class AlertService implements AlertUseCase {

    private final AlertRepository repository;
    private final AlertReadRepository readRepository;
    private final KitchenRepository kitchenRepository;
    private final ProductRepository productRepository;
    private final StockBatchRepository stockBatchRepository;
    private final KitchenAccessGuard accessGuard;
    private final AlertPublisher publisher;
    private final OpenFoodFactsClient openFoodFactsClient;

    @Override
    public Alert create(CreateAlertRequest request) {
        accessGuard.assertAccess(request.kitchenId());
        Kitchen kitchen = kitchenRepository.findById(request.kitchenId())
            .orElseThrow(() -> new ResourceNotFoundException("Cozinha não encontrada."));

        Product product = null;
        if (request.productId() != null) {
            product = productRepository.findById(request.productId())
                .orElseThrow(() -> new ResourceNotFoundException("Produto não encontrado."));
        }

        StockBatch batch = null;
        if (request.batchId() != null) {
            batch = stockBatchRepository.findById(request.batchId())
                .orElseThrow(() -> new ResourceNotFoundException("Lote não encontrado."));
            // o lote precisa ser da cozinha do alerta (e do produto informado), senão o alerta expõe
            // dados de outra cozinha
            if (!batch.getKitchen().getId().equals(kitchen.getId())) {
                throw new BusinessRuleException("O lote não pertence à cozinha do alerta.");
            }
            if (product != null && !batch.getProduct().getId().equals(product.getId())) {
                throw new BusinessRuleException("O lote não é do produto informado.");
            }
        }

        Alert alert = Alert.builder()
            .type(request.type())
            .severity(request.severity())
            .batch(batch)
            .product(product)
            .kitchen(kitchen)
            .message(request.message())
            .read(false)
            .build();

        return repository.save(alert);
    }

    @Override
    public UnregisteredProductReport reportUnregisteredProduct(Integer kitchenId, String barcode) {
        accessGuard.assertAccess(kitchenId);
        Kitchen kitchen = kitchenRepository.findById(kitchenId)
            .orElseThrow(() -> new ResourceNotFoundException("Cozinha não encontrada."));
        if (productRepository.existsByBarcode(barcode)) {
            throw new BusinessRuleException("Esse produto já está cadastrado no catálogo.");
        }

        Optional<Alert> open = repository.findFirstOpenByOrigin(kitchenId, AlertType.UNREGISTERED_PRODUCT, barcode);
        if (open.isPresent()) {
            return new UnregisteredProductReport(open.get(), false);
        }

        String name = lookupProductName(barcode);
        String message = name == null
            ? "Produto não cadastrado: " + barcode
            : "Produto não cadastrado: " + name + " (" + barcode + ")";
        Alert alert = publisher.notifyProfile(kitchen, Roles.SUPERVISOR_PROFILE, AlertType.UNREGISTERED_PRODUCT,
            AlertSeverity.MEDIUM, message, barcode);
        return new UnregisteredProductReport(alert, true);
    }

    // O nome vem do OpenFoodFacts; se ele não conhecer o código ou estiver fora do ar, o aviso sai só com o código.
    private String lookupProductName(String barcode) {
        try {
            return openFoodFactsClient.findByBarcode(barcode)
                .map(OpenFoodFactsProduct::name)
                .filter(name -> !name.isBlank())
                .orElse(null);
        } catch (RuntimeException ex) {
            log.warn("OpenFoodFacts indisponível ao montar o aviso de produto não cadastrado: {}", ex.toString());
            return null;
        }
    }

    @Override
    public AlertView findById(Integer id) {
        Alert alert = load(id);
        return new AlertView(alert, isReadByCurrentUser(alert));
    }

    @Override
    public List<AlertView> listByKitchen(Integer kitchenId) {
        accessGuard.assertAccess(kitchenId);
        User user = accessGuard.currentUser();
        List<Alert> alerts = repository.findVisible(kitchenId, user.getId(), user.getProfile().getId(),
            seesKitchenWide(user));
        if (alerts.isEmpty()) {
            return List.of();
        }
        Set<Integer> readByMe = new HashSet<>(
            readRepository.findReadAlertIds(user.getId(), alerts.stream().map(Alert::getId).toList()));
        return alerts.stream()
            .map(alert -> new AlertView(alert, Boolean.TRUE.equals(alert.getRead()) || readByMe.contains(alert.getId())))
            .toList();
    }

    @Override
    public List<AlertView> listUnreadByKitchen(Integer kitchenId) {
        return findUnread(kitchenId).stream()
            .map(alert -> new AlertView(alert, false))
            .toList();
    }

    @Override
    public AlertView markAsRead(Integer id) {
        Alert alert = load(id);
        AlertReadId readId = new AlertReadId(alert.getId(), accessGuard.currentUser().getId());
        if (!readRepository.existsById(readId)) {
            readRepository.save(new AlertRead(readId, LocalDateTime.now()));
        }
        return new AlertView(alert, true);
    }

    @Override
    @Transactional
    public void markAllAsRead(Integer kitchenId) {
        User user = accessGuard.currentUser();
        LocalDateTime now = LocalDateTime.now();
        readRepository.saveAll(findUnread(kitchenId).stream()
            .map(alert -> new AlertRead(new AlertReadId(alert.getId(), user.getId()), now))
            .toList());
    }

    @Override
    @Transactional
    public void delete(Integer id) {
        Alert alert = load(id);
        readRepository.deleteByAlertId(alert.getId());
        repository.delete(alert);
    }

    private List<Alert> findUnread(Integer kitchenId) {
        accessGuard.assertAccess(kitchenId);
        User user = accessGuard.currentUser();
        return repository.findUnreadVisible(kitchenId, user.getId(), user.getProfile().getId(), seesKitchenWide(user));
    }

    private Alert load(Integer id) {
        Alert alert = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Alerta não encontrado."));
        accessGuard.assertAccess(alert.getKitchen().getId());
        if (!isVisibleTo(alert, accessGuard.currentUser())) {
            throw new AccessDeniedException("Esse alerta não é para você.");
        }
        return alert;
    }

    private boolean isReadByCurrentUser(Alert alert) {
        return Boolean.TRUE.equals(alert.getRead())
            || readRepository.existsById(new AlertReadId(alert.getId(), accessGuard.currentUser().getId()));
    }

    // Mesma regra das consultas do AlertRepository.
    private static boolean isVisibleTo(Alert alert, User user) {
        if (alert.getRecipient() != null) {
            return alert.getRecipient().getId().equals(user.getId());
        }
        if (alert.getTargetProfile() != null) {
            return alert.getTargetProfile().getId().equals(user.getProfile().getId());
        }
        return seesKitchenWide(user);
    }

    // Alertas de estoque e validade são de quem cuida do estoque; o comprador só recebe os dele.
    private static boolean seesKitchenWide(User user) {
        return !Roles.COMPRADOR_PROFILE.equalsIgnoreCase(user.getProfile().getAccessType());
    }
}
