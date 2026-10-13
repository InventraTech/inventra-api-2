package com.inventra.api.core.service.kitchenaccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.user.User;
import com.inventra.api.core.service.alert.AlertPublisher;
import com.inventra.api.core.service.kitchen.KitchenUseCase;
import com.inventra.api.infrastructure.exception.BusinessRuleException;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.KitchenRepository;
import com.inventra.api.infrastructure.repository.UserRepository;
import com.inventra.api.infrastructure.security.KitchenAccessGuard;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KitchenAccessRequestServiceTest {

    private static final Integer KITCHEN_ID = 1;
    private static final String REQUEST_ID = "req-1";
    private static final Duration TTL = Duration.ofDays(7);

    @Mock private KitchenAccessRequestStore store;
    @Mock private KitchenUseCase kitchenUseCase;
    @Mock private KitchenRepository kitchenRepository;
    @Mock private UserRepository userRepository;
    @Mock private KitchenAccessGuard accessGuard;
    @Mock private AlertPublisher alertPublisher;

    private KitchenAccessRequestService service;

    private Kitchen kitchen;
    private User supervisor;
    private User applicant;

    @BeforeEach
    void setUp() {
        service = new KitchenAccessRequestService(store, kitchenUseCase, kitchenRepository, userRepository, accessGuard,
                alertPublisher, TTL);
        kitchen = Kitchen.builder().id(KITCHEN_ID).name("Central").active(true).build();
        supervisor = User.builder().id(UUID.randomUUID()).name("Sup").kitchen(kitchen).active(true).build();
        applicant = User.builder().id(UUID.randomUUID()).name("Ana").email("ana@x.com").active(true).build();

        // por padrão o usuário logado é o supervisor; os testes de "request" trocam para o candidato
        when(accessGuard.currentUser()).thenReturn(supervisor);
        when(accessGuard.isSupervisor()).thenReturn(true);
        when(accessGuard.currentKitchenId()).thenReturn(KITCHEN_ID);
    }

    private void loggedAsApplicant() {
        when(accessGuard.currentUser()).thenReturn(applicant);
        when(accessGuard.isSupervisor()).thenReturn(false);
        when(accessGuard.currentKitchenId()).thenReturn(null);
    }

    private KitchenAccessRequest pending() {
        return new KitchenAccessRequest(REQUEST_ID, applicant.getId(), "Ana", "ana@x.com", KITCHEN_ID, "Central",
                KitchenAccessRequestStatus.PENDING, Instant.now(), null, null, null);
    }

    private KitchenAccessRequest decided(KitchenAccessRequestStatus status) {
        return new KitchenAccessRequest(REQUEST_ID, applicant.getId(), "Ana", "ana@x.com", KITCHEN_ID, "Central",
                status, Instant.now(), Instant.now(), supervisor.getId(), null);
    }

    // ---------- request ----------

    @Test
    void requestSavesPendingRequestWithTtl() {
        loggedAsApplicant();
        when(kitchenUseCase.findByCode("AB12CD34")).thenReturn(kitchen);
        when(store.findLatestByUser(applicant.getId())).thenReturn(Optional.empty());

        KitchenAccessRequest result = service.request("AB12CD34", null);

        assertTrue(result.isPending());
        assertEquals(applicant.getId(), result.userId());
        assertEquals(KITCHEN_ID, result.kitchenId());
        verify(store).savePending(result, TTL);
    }

    @Test
    void requestRejectsUserAlreadyLinkedToAKitchen() {
        when(accessGuard.currentUser()).thenReturn(applicant);

        assertThrows(BusinessRuleException.class, () -> service.request("AB12CD34", null));
        verify(store, never()).savePending(any(), any());
    }

    @Test
    void requestRejectsWhenAPendingRequestAlreadyExists() {
        loggedAsApplicant();
        when(kitchenUseCase.findByCode("AB12CD34")).thenReturn(kitchen);
        when(store.findLatestByUser(applicant.getId())).thenReturn(Optional.of(pending()));

        assertThrows(BusinessRuleException.class, () -> service.request("AB12CD34", null));
        verify(store, never()).savePending(any(), any());
    }

    @Test
    void requestAllowsNewRequestAfterThePreviousOneWasRejected() {
        loggedAsApplicant();
        when(kitchenUseCase.findByCode("AB12CD34")).thenReturn(kitchen);
        when(store.findLatestByUser(applicant.getId()))
                .thenReturn(Optional.of(decided(KitchenAccessRequestStatus.REJECTED)));

        KitchenAccessRequest result = service.request("AB12CD34", null);

        assertTrue(result.isPending());
    }

    @Test
    void requestPropagatesUnknownCode() {
        loggedAsApplicant();
        when(kitchenUseCase.findByCode("XXXX")).thenThrow(new ResourceNotFoundException("Cozinha não encontrada."));

        assertThrows(ResourceNotFoundException.class, () -> service.request("XXXX", null));
    }

    // ---------- findMine / list ----------

    @Test
    void findMineThrowsWhenThereIsNoRequest() {
        loggedAsApplicant();
        when(store.findLatestByUser(applicant.getId())).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.findMine());
    }

    @Test
    void listForMyKitchenFiltersByStatus() {
        when(store.listByKitchen(KITCHEN_ID)).thenReturn(List.of(pending(), decided(KitchenAccessRequestStatus.APPROVED)));

        assertEquals(1, service.listForMyKitchen(KitchenAccessRequestStatus.PENDING).size());
        assertEquals(2, service.listForMyKitchen(null).size());
    }

    @Test
    void listForMyKitchenIsBlockedForNonSupervisor() {
        when(accessGuard.isSupervisor()).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> service.listForMyKitchen(null));
    }

    @Test
    void listForMyKitchenIsEmptyForSupervisorWithoutKitchen() {
        when(accessGuard.currentKitchenId()).thenReturn(null);

        assertTrue(service.listForMyKitchen(null).isEmpty());
        verify(store, never()).listByKitchen(any());
    }

    // ---------- approve ----------

    @Test
    void approveLinksApplicantToKitchenAndRecordsDecision() {
        when(store.find(REQUEST_ID)).thenReturn(Optional.of(pending()));
        when(userRepository.findById(applicant.getId())).thenReturn(Optional.of(applicant));
        when(kitchenRepository.findById(KITCHEN_ID)).thenReturn(Optional.of(kitchen));
        when(store.decide(eq(REQUEST_ID), eq(KitchenAccessRequestStatus.APPROVED), eq(supervisor.getId()), any(), any()))
                .thenReturn(true);

        service.approve(REQUEST_ID);

        assertSame(kitchen, applicant.getKitchen());
        verify(userRepository).saveAndFlush(applicant);
    }

    @Test
    void approveFailsWhenAnotherSupervisorDecidedFirst() {
        when(store.find(REQUEST_ID)).thenReturn(Optional.of(pending()));
        when(userRepository.findById(applicant.getId())).thenReturn(Optional.of(applicant));
        when(kitchenRepository.findById(KITCHEN_ID)).thenReturn(Optional.of(kitchen));
        when(store.decide(any(), any(), any(), any(), any())).thenReturn(false);

        assertThrows(BusinessRuleException.class, () -> service.approve(REQUEST_ID));
    }

    @Test
    void approveRejectsApplicantAlreadyLinkedToAKitchen() {
        applicant.setKitchen(Kitchen.builder().id(99).build());
        when(store.find(REQUEST_ID)).thenReturn(Optional.of(pending()));
        when(userRepository.findById(applicant.getId())).thenReturn(Optional.of(applicant));

        assertThrows(BusinessRuleException.class, () -> service.approve(REQUEST_ID));
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void approveRejectsDeactivatedApplicant() {
        applicant.setActive(false);
        when(store.find(REQUEST_ID)).thenReturn(Optional.of(pending()));
        when(userRepository.findById(applicant.getId())).thenReturn(Optional.of(applicant));

        assertThrows(BusinessRuleException.class, () -> service.approve(REQUEST_ID));
    }

    @Test
    void approveRejectsInactiveKitchen() {
        kitchen.setActive(false);
        when(store.find(REQUEST_ID)).thenReturn(Optional.of(pending()));
        when(userRepository.findById(applicant.getId())).thenReturn(Optional.of(applicant));
        when(kitchenRepository.findById(KITCHEN_ID)).thenReturn(Optional.of(kitchen));

        assertThrows(BusinessRuleException.class, () -> service.approve(REQUEST_ID));
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void approveRejectsAlreadyDecidedRequest() {
        when(store.find(REQUEST_ID)).thenReturn(Optional.of(decided(KitchenAccessRequestStatus.REJECTED)));

        assertThrows(BusinessRuleException.class, () -> service.approve(REQUEST_ID));
    }

    @Test
    void approveFailsForExpiredOrUnknownRequest() {
        when(store.find(REQUEST_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.approve(REQUEST_ID));
    }

    @Test
    void approveIsBlockedForRequestOfAnotherKitchen() {
        when(store.find(REQUEST_ID)).thenReturn(Optional.of(pending()));
        doThrow(new AccessDeniedException("sem acesso")).when(accessGuard).assertAccess(KITCHEN_ID);

        assertThrows(AccessDeniedException.class, () -> service.approve(REQUEST_ID));
    }

    @Test
    void approveIsBlockedForNonSupervisor() {
        when(accessGuard.isSupervisor()).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> service.approve(REQUEST_ID));
        verify(store, never()).find(any());
    }

    // ---------- reject ----------

    @Test
    void rejectRecordsDecisionWithReason() {
        when(store.find(REQUEST_ID)).thenReturn(Optional.of(pending()));
        when(store.decide(eq(REQUEST_ID), eq(KitchenAccessRequestStatus.REJECTED), eq(supervisor.getId()),
                eq("Não conheço"), any())).thenReturn(true);

        service.reject(REQUEST_ID, "Não conheço");

        verify(store).decide(eq(REQUEST_ID), eq(KitchenAccessRequestStatus.REJECTED), eq(supervisor.getId()),
                eq("Não conheço"), any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectFailsWhenRequestWasDecidedMeanwhile() {
        when(store.find(REQUEST_ID)).thenReturn(Optional.of(pending()));
        when(store.decide(any(), any(), any(), any(), any())).thenReturn(false);

        assertThrows(BusinessRuleException.class, () -> service.reject(REQUEST_ID, null));
    }
}
