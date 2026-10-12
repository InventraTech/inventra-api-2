package com.inventra.api.core.service.kitchenaccess;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inventra.api.core.domain.alert.AlertType;
import com.inventra.api.core.domain.alert.enums.AlertSeverity;
import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.user.User;
import com.inventra.api.core.service.alert.AlertPublisher;
import com.inventra.api.core.service.kitchen.KitchenUseCase;
import com.inventra.api.infrastructure.exception.BusinessRuleException;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.KitchenRepository;
import com.inventra.api.infrastructure.repository.UserRepository;
import com.inventra.api.infrastructure.security.KitchenAccessGuard;
import com.inventra.api.infrastructure.security.Roles;

// Fluxo: usuário sem cozinha pede entrada (pelo código de convite ou pela descoberta) -> supervisor da cozinha aprova ou recusa. Aprovar grava
// a cozinha no usuário; como o vínculo é lido do banco a cada requisição, o token dele continua valendo.
@Service
public class KitchenAccessRequestService implements KitchenAccessRequestUseCase {

    private static final Logger log = LoggerFactory.getLogger(KitchenAccessRequestService.class);

    private final KitchenAccessRequestStore store;
    private final KitchenUseCase kitchenUseCase;
    private final KitchenRepository kitchenRepository;
    private final UserRepository userRepository;
    private final KitchenAccessGuard accessGuard;
    private final AlertPublisher alertPublisher;
    private final Duration pendingTtl;

    public KitchenAccessRequestService(KitchenAccessRequestStore store, KitchenUseCase kitchenUseCase,
                                       KitchenRepository kitchenRepository, UserRepository userRepository,
                                       KitchenAccessGuard accessGuard, AlertPublisher alertPublisher,
                                       @Value("${app.kitchen-access-request.ttl:7d}") Duration pendingTtl) {
        this.store = store;
        this.kitchenUseCase = kitchenUseCase;
        this.kitchenRepository = kitchenRepository;
        this.userRepository = userRepository;
        this.accessGuard = accessGuard;
        this.alertPublisher = alertPublisher;
        this.pendingTtl = pendingTtl;
    }

    public Duration pendingTtl() {
        return pendingTtl;
    }

    @Override
    public KitchenAccessRequest request(String code, Integer kitchenId) {
        boolean hasCode = code != null && !code.isBlank();
        if (hasCode == (kitchenId != null)) {
            throw new IllegalArgumentException("Informe kitchenId ou code (apenas um dos dois).");
        }
        User current = accessGuard.currentUser();
        if (accessGuard.currentKitchenId() != null) {
            throw new BusinessRuleException("Você já está vinculado a uma cozinha.");
        }
        // a busca por código já limita as tentativas do usuário e ignora cozinha desativada
        Kitchen kitchen = hasCode ? kitchenUseCase.findByCode(code) : findDiscoverable(kitchenId);

        if (store.findLatestByUser(current.getId()).filter(KitchenAccessRequest::isPending).isPresent()) {
            throw new BusinessRuleException("Você já tem uma solicitação de entrada pendente.");
        }

        KitchenAccessRequest request = new KitchenAccessRequest(
                UUID.randomUUID().toString(),
                current.getId(),
                current.getName(),
                current.getEmail(),
                kitchen.getId(),
                kitchen.getName(),
                KitchenAccessRequestStatus.PENDING,
                Instant.now(),
                null,
                null,
                null);
        store.savePending(request, pendingTtl);
        notifySupervisors(kitchen, request);
        return request;
    }

    // O pedido já está gravado: se o aviso falhar, o supervisor ainda o encontra na tela de membros.
    private void notifySupervisors(Kitchen kitchen, KitchenAccessRequest request) {
        try {
            alertPublisher.notifyProfile(kitchen, Roles.SUPERVISOR_PROFILE, AlertType.ACCESS_REQUEST,
                AlertSeverity.MEDIUM, request.userName() + " pediu para entrar na cozinha.", request.id());
        } catch (RuntimeException ex) {
            log.warn("Não foi possível avisar os supervisores do pedido de entrada {}: {}", request.id(), ex.toString());
        }
    }

    // Pelo id só entra cozinha que aparece na descoberta: ativa e aceitando pedidos. Quem fechou os pedidos
    // continua recebendo gente pelo código de convite.
    private Kitchen findDiscoverable(Integer kitchenId) {
        Kitchen kitchen = kitchenRepository.findById(kitchenId)
            .filter(found -> Boolean.TRUE.equals(found.getActive()))
            .orElseThrow(() -> new ResourceNotFoundException("Cozinha não encontrada."));
        if (!Boolean.TRUE.equals(kitchen.getAcceptingRequests())) {
            throw new BusinessRuleException("Essa cozinha não está aceitando novos pedidos de entrada.");
        }
        return kitchen;
    }

    @Override
    public KitchenAccessRequest findMine() {
        return store.findLatestByUser(accessGuard.currentUser().getId())
            .orElseThrow(() -> new ResourceNotFoundException("Você não tem solicitação de entrada em cozinha."));
    }

    @Override
    public List<KitchenAccessRequest> listForMyKitchen(KitchenAccessRequestStatus status) {
        assertSupervisor();
        Integer kitchenId = accessGuard.currentKitchenId();
        if (kitchenId == null) {
            return List.of();
        }
        return store.listByKitchen(kitchenId).stream()
            .filter(request -> status == null || request.status() == status)
            .toList();
    }

    @Override
    @Transactional
    public KitchenAccessRequest approve(String id) {
        KitchenAccessRequest request = loadPendingForMyKitchen(id);

        User applicant = userRepository.findById(request.userId())
            .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado."));
        if (!Boolean.TRUE.equals(applicant.getActive())) {
            throw new BusinessRuleException("O usuário está desativado.");
        }
        if (applicant.getKitchen() != null) {
            throw new BusinessRuleException("O usuário já está vinculado a uma cozinha.");
        }
        Kitchen kitchen = kitchenRepository.findById(request.kitchenId())
            .orElseThrow(() -> new ResourceNotFoundException("Cozinha não encontrada."));
        if (!Boolean.TRUE.equals(kitchen.getActive())) {
            throw new BusinessRuleException("Cozinha desativada: reative a cozinha para aprovar entradas.");
        }

        // Postgres primeiro: se a decisão no Redis falhar (ou perder a corrida para outro supervisor),
        // a exceção desfaz o vínculo.
        applicant.setKitchen(kitchen);
        userRepository.saveAndFlush(applicant);
        decide(request, KitchenAccessRequestStatus.APPROVED, null);
        return store.find(id).orElse(request);
    }

    @Override
    public KitchenAccessRequest reject(String id, String reason) {
        KitchenAccessRequest request = loadPendingForMyKitchen(id);
        decide(request, KitchenAccessRequestStatus.REJECTED, reason);
        return store.find(id).orElse(request);
    }

    private void decide(KitchenAccessRequest request, KitchenAccessRequestStatus status, String reason) {
        boolean decided = store.decide(request.id(), status, accessGuard.currentUser().getId(), reason, Instant.now());
        if (!decided) {
            throw new BusinessRuleException("Essa solicitação já foi decidida ou expirou.");
        }
        // pedido resolvido: o aviso deixa de ficar pendente para os supervisores
        alertPublisher.resolve(request.kitchenId(), AlertType.ACCESS_REQUEST, request.id());
    }

    private KitchenAccessRequest loadPendingForMyKitchen(String id) {
        assertSupervisor();
        KitchenAccessRequest request = store.find(id)
            .orElseThrow(() -> new ResourceNotFoundException("Solicitação não encontrada ou expirada."));
        accessGuard.assertAccess(request.kitchenId());
        if (!request.isPending()) {
            throw new BusinessRuleException("Essa solicitação já foi decidida.");
        }
        return request;
    }

    private void assertSupervisor() {
        if (!accessGuard.isSupervisor()) {
            throw new org.springframework.security.access.AccessDeniedException("Somente supervisor pode gerenciar solicitações de entrada.");
        }
    }
}
