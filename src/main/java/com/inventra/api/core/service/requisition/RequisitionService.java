package com.inventra.api.core.service.requisition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inventra.api.core.service.requisition.model.request.AddRequisitionItemRequest;
import com.inventra.api.core.service.requisition.model.request.CreateRequisitionRequest;
import com.inventra.api.core.service.alert.AlertPublisher;
import com.inventra.api.core.service.stockbatch.StockBatchUseCase;
import com.inventra.api.core.domain.alert.AlertType;
import com.inventra.api.core.domain.alert.enums.AlertSeverity;
import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.product.Product;
import com.inventra.api.core.domain.product.ProductSupplier;
import com.inventra.api.core.domain.product.ProductSupplierId;
import com.inventra.api.core.domain.requisition.Requisition;
import com.inventra.api.core.domain.requisition.RequisitionItem;
import com.inventra.api.core.domain.requisition.enums.RequisitionStatus;
import com.inventra.api.core.domain.requisition.enums.RequisitionType;
import com.inventra.api.core.domain.supplier.Supplier;
import com.inventra.api.core.domain.user.User;
import com.inventra.api.infrastructure.exception.BusinessRuleException;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.KitchenRepository;
import com.inventra.api.infrastructure.repository.ProductRepository;
import com.inventra.api.infrastructure.repository.ProductSupplierRepository;
import com.inventra.api.infrastructure.repository.RequisitionItemRepository;
import com.inventra.api.infrastructure.repository.RequisitionRepository;
import com.inventra.api.infrastructure.repository.SupplierRepository;
import com.inventra.api.infrastructure.repository.UserRepository;
import com.inventra.api.infrastructure.security.KitchenAccessGuard;
import com.inventra.api.infrastructure.security.Roles;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RequisitionService implements RequisitionUseCase {

    private final RequisitionRepository repository;
    private final RequisitionItemRepository itemRepository;
    private final KitchenRepository kitchenRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final SupplierRepository supplierRepository;
    private final ProductSupplierRepository productSupplierRepository;
    private final StockBatchUseCase stockBatchUseCase;
    private final KitchenAccessGuard accessGuard;
    private final AlertPublisher alertPublisher;

    @Override
    public Requisition create(CreateRequisitionRequest request) {
        accessGuard.assertAccess(request.kitchenId());
        Kitchen kitchen = kitchenRepository.findById(request.kitchenId())
            .orElseThrow(() -> new ResourceNotFoundException("Cozinha não encontrada."));
        if (!Boolean.TRUE.equals(kitchen.getActive())) {
            throw new BusinessRuleException("Cozinha desativada: reative a cozinha para abrir requisições.");
        }
        // o solicitante é sempre o usuário logado (não vem do body)
        User requester = userRepository.findById(accessGuard.currentUser().getId())
            .orElseThrow(() -> new ResourceNotFoundException("Usuário requisitante não encontrado."));

        Requisition requisition = Requisition.builder()
            .type(request.type())
            .origin(request.origin())
            .status(RequisitionStatus.DRAFT)
            .kitchen(kitchen)
            .requester(requester)
            .build();

        return repository.save(requisition);
    }

    @Override
    public Requisition findById(Integer requisitionId) {
        Requisition requisition = loadRequisition(requisitionId);
        assertOwnerOrSupervisor(requisition);
        return requisition;
    }

    @Override
    public Requisition addItem(Integer requisitionId, AddRequisitionItemRequest request) {
        Requisition requisition = findDraftRequisition(requisitionId);
        assertOwnerOrSupervisor(requisition);

        Product product = productRepository.findById(request.productId())
            .orElseThrow(() -> new ResourceNotFoundException("Produto não encontrado."));
        if (!product.isActive()) {
            throw new BusinessRuleException("Produto desativado não pode ser requisitado.");
        }

        Supplier suggestedSupplier = null;
        if (request.suggestedSupplierId() != null) {
            suggestedSupplier = supplierRepository.findById(request.suggestedSupplierId())
                .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado."));
        }

        BigDecimal estimatedPrice = request.estimatedPrice() != null
            ? request.estimatedPrice()
            : suggestReferencePrice(request.productId(), request.suggestedSupplierId());

        RequisitionItem item = RequisitionItem.builder()
            .requisition(requisition)
            .product(product)
            .suggestedSupplier(suggestedSupplier)
            .quantity(request.quantity())
            .estimatedPrice(estimatedPrice)
            .note(request.note())
            .build();

        itemRepository.save(item);
        return requisition;
    }

    @Override
    public Requisition removeItem(Integer requisitionId, Integer itemId) {
        Requisition requisition = findDraftRequisition(requisitionId);
        assertOwnerOrSupervisor(requisition);

        RequisitionItem item = itemRepository.findById(itemId)
            .orElseThrow(() -> new ResourceNotFoundException("Item não encontrado."));
        if (!item.getRequisition().getId().equals(requisitionId)) {
            throw new BusinessRuleException("Item não pertence a essa requisição.");
        }

        itemRepository.delete(item);
        return requisition;
    }

    @Override
    @Transactional
    public Requisition submit(Integer requisitionId) {
        Requisition requisition = findDraftRequisition(requisitionId);
        assertOwnerOrSupervisor(requisition);

        if (itemRepository.countByRequisitionId(requisitionId) == 0) {
            throw new BusinessRuleException("Requisição sem itens não pode ser enviada.");
        }

        requisition.setStatus(RequisitionStatus.UNDER_REVIEW);
        Requisition submitted = repository.save(requisition);
        alertPublisher.notifyProfile(submitted.getKitchen(), Roles.SUPERVISOR_PROFILE, AlertType.REQUISITION_SUBMITTED,
            AlertSeverity.MEDIUM, "Nova requisição #" + requisitionId + " para análise.", refKey(requisitionId));
        return submitted;
    }

    @Override
    @Transactional
    public Requisition approve(Integer requisitionId) {
        Requisition requisition = findRequisitionUnderReview(requisitionId);
        Integer kitchenId = requisition.getKitchen().getId();
        RequisitionType type = requisition.getType();
        if (itemRepository.countByRequisitionId(requisitionId) == 0) {
            throw new BusinessRuleException("Requisição sem itens não pode ser aprovada.");
        }

        // o aprovador é sempre o usuário logado (não vem do body);
        // sp_approve_requisition muda status/aprovador e o trigger trg_requisition_approval preenche approved_at
        repository.callApproveRequisition(requisitionId, accessGuard.currentUser().getId());

        // PURCHASE não mexe no estoque: a entrada acontece quando a mercadoria chega (POST /api/stock-batches),
        // já que o item de requisição não tem lote/validade pra virar um lote.
        // TRANSFER é a saída da cozinha da requisição (o destino é texto livre em origin, sem cozinha
        // vinculada); a cozinha que recebe registra a entrada dela pelo POST /api/stock-batches.
        // Cada baixa grava a movimentação com a requisição: é por ela que o cancelamento estorna.
        if (type == RequisitionType.CONSUMPTION || type == RequisitionType.TRANSFER) {
            List<RequisitionItem> items = itemRepository.findByRequisitionId(requisitionId);
            for (RequisitionItem item : items) {
                stockBatchUseCase.consumeForRequisition(kitchenId, item.getProduct().getId(), item.getQuantity(),
                    requisitionId);
            }
        }

        return closeReview(requisitionId, kitchenId, AlertType.REQUISITION_APPROVED,
            "Sua requisição #" + requisitionId + " foi aprovada.");
    }

    @Override
    @Transactional
    public Requisition reject(Integer requisitionId, String reason) {
        Integer kitchenId = findRequisitionUnderReview(requisitionId).getKitchen().getId();

        repository.callRejectRequisition(requisitionId, accessGuard.currentUser().getId(), reason);
        return closeReview(requisitionId, kitchenId, AlertType.REQUISITION_REJECTED,
            "Sua requisição #" + requisitionId + " foi recusada: " + reason);
    }

    @Override
    @Transactional
    public Requisition cancel(Integer requisitionId, String reason) {
        Requisition requisition = loadRequisition(requisitionId);
        assertOwnerOrSupervisor(requisition);
        Integer kitchenId = requisition.getKitchen().getId();
        // cancelar uma requisição já aprovada (estoque já baixado) é decisão do supervisor
        if (requisition.getStatus() == RequisitionStatus.APPROVED && !accessGuard.isSupervisor()) {
            throw new AccessDeniedException("Somente supervisor pode cancelar uma requisição já aprovada.");
        }

        // sp_cancel_requisition aceita DRAFT, UNDER_REVIEW ou APPROVED; na aprovada, devolve aos lotes o que
        // a aprovação baixou (as movimentações WRITE_OFF da requisição) e grava um ajuste por lote
        repository.callCancelRequisition(requisitionId, reason, accessGuard.currentUser().getId());
        // cancelada antes da decisão: o aviso de "nova requisição" deixa de ficar pendente
        alertPublisher.resolve(kitchenId, AlertType.REQUISITION_SUBMITTED, refKey(requisitionId));
        return reload(requisitionId);
    }

    @Override
    public List<Requisition> list(Integer kitchenId, RequisitionStatus status, UUID requesterId) {
        Integer scopedKitchenId = kitchenId;
        if (scopedKitchenId != null) {
            accessGuard.assertAccess(scopedKitchenId);
        } else {
            scopedKitchenId = accessGuard.currentKitchenId();
            if (scopedKitchenId == null) {
                return List.of();
            }
        }

        List<Requisition> requisitions;
        if (status != null && requesterId != null) {
            requisitions = repository.findByKitchenIdAndStatusAndRequesterId(scopedKitchenId, status, requesterId);
        } else if (status != null) {
            requisitions = repository.findByKitchenIdAndStatus(scopedKitchenId, status);
        } else if (requesterId != null) {
            requisitions = repository.findByKitchenIdAndRequesterId(scopedKitchenId, requesterId);
        } else {
            requisitions = repository.findByKitchenId(scopedKitchenId);
        }

        // rascunho ainda não foi enviado: só o próprio solicitante o vê
        UUID currentUserId = accessGuard.currentUser().getId();
        return requisitions.stream()
            .filter(requisition -> requisition.getStatus() != RequisitionStatus.DRAFT
                || requisition.getRequester().getId().equals(currentUserId))
            .toList();
    }

    @Override
    public Map<Integer, RequisitionItemsSummary> summarizeItems(Collection<Requisition> requisitions) {
        if (requisitions.isEmpty()) {
            return Map.of();
        }
        Map<Integer, List<RequisitionItem>> itemsByRequisition = new HashMap<>();
        itemRepository.findByRequisitionIdsWithProduct(requisitions.stream().map(Requisition::getId).toList())
            .forEach(item -> itemsByRequisition
                .computeIfAbsent(item.getRequisition().getId(), id -> new ArrayList<>())
                .add(item));

        Map<Integer, RequisitionItemsSummary> summaries = new HashMap<>();
        itemsByRequisition.forEach((requisitionId, items) -> {
            BigDecimal total = items.stream()
                .filter(item -> item.getEstimatedPrice() != null)
                .map(item -> item.getQuantity().multiply(item.getEstimatedPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
            List<String> names = items.stream()
                .limit(RequisitionItemsSummary.MAX_ITEM_NAMES)
                .map(item -> item.getProduct().getName())
                .toList();
            summaries.put(requisitionId, new RequisitionItemsSummary(items.size(), total, names));
        });
        return summaries;
    }

    // Requisição decidida: o aviso de "nova requisição" some para os supervisores e quem pediu é avisado
    // (a não ser que a própria pessoa tenha decidido). Devolve a requisição relida: a procedure e o
    // "resolve" limpam as entidades em memória.
    private Requisition closeReview(Integer requisitionId, Integer kitchenId, String type, String message) {
        alertPublisher.resolve(kitchenId, AlertType.REQUISITION_SUBMITTED, refKey(requisitionId));
        Requisition decided = reload(requisitionId);
        if (!decided.getRequester().getId().equals(accessGuard.currentUser().getId())) {
            alertPublisher.notifyUser(decided.getKitchen(), decided.getRequester(), type, AlertSeverity.MEDIUM, message,
                refKey(requisitionId));
        }
        return decided;
    }

    private static String refKey(Integer requisitionId) {
        return String.valueOf(requisitionId);
    }

    @Override
    public List<RequisitionItem> listItems(Integer requisitionId) {
        loadRequisition(requisitionId);
        return itemRepository.findByRequisitionId(requisitionId);
    }

    // Sem preço informado: usa o referencePrice já vinculado (linkSupplier) como sugestão, se existir.
    private BigDecimal suggestReferencePrice(Integer productId, Integer supplierId) {
        if (supplierId == null) {
            return null;
        }
        return productSupplierRepository.findById(new ProductSupplierId(productId, supplierId))
            .map(ProductSupplier::getReferencePrice)
            .orElse(null);
    }

    private Requisition loadRequisition(Integer requisitionId) {
        Requisition requisition = repository.findById(requisitionId)
            .orElseThrow(() -> new ResourceNotFoundException("Requisição não encontrada."));
        accessGuard.assertAccess(requisition.getKitchen().getId());
        return requisition;
    }

    private Requisition reload(Integer requisitionId) {
        return repository.findById(requisitionId)
            .orElseThrow(() -> new ResourceNotFoundException("Requisição não encontrada."));
    }

    // Comprador só mexe nas próprias requisições; supervisor mexe em todas da cozinha.
    private void assertOwnerOrSupervisor(Requisition requisition) {
        boolean owner = requisition.getRequester().getId().equals(accessGuard.currentUser().getId());
        if (!owner && !accessGuard.isSupervisor()) {
            throw new AccessDeniedException("Você só pode alterar as próprias requisições.");
        }
    }

    // Itens só mudam enquanto a requisição é rascunho: depois de enviada, o supervisor analisa o que recebeu.
    private Requisition findDraftRequisition(Integer requisitionId) {
        Requisition requisition = loadRequisition(requisitionId);

        if (requisition.getStatus() != RequisitionStatus.DRAFT) {
            throw new BusinessRuleException("Requisição já foi enviada e não pode mais ser alterada.");
        }

        return requisition;
    }

    private Requisition findRequisitionUnderReview(Integer requisitionId) {
        Requisition requisition = loadRequisition(requisitionId);

        if (requisition.getStatus() == RequisitionStatus.DRAFT) {
            throw new BusinessRuleException("Requisição ainda não foi enviada para análise.");
        }
        if (requisition.getStatus() != RequisitionStatus.UNDER_REVIEW) {
            throw new BusinessRuleException("Requisição não está mais em análise.");
        }

        return requisition;
    }
}
