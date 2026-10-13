package com.inventra.api.core.service.kitchen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.user.User;
import com.inventra.api.core.service.kitchen.model.request.CreateKitchenRequest;
import com.inventra.api.core.service.kitchen.model.request.UpdateKitchenRequest;
import com.inventra.api.infrastructure.exception.BusinessRuleException;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.KitchenRepository;
import com.inventra.api.infrastructure.repository.UserRepository;
import com.inventra.api.infrastructure.security.KitchenAccessGuard;
import com.inventra.api.core.service.kitchenaccess.KitchenAccessRequestStore;
import com.inventra.api.infrastructure.security.KitchenCodeLookupLimiter;
import com.inventra.api.infrastructure.security.KitchenDiscoveryLimiter;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KitchenServiceTest {

    private static final Integer KITCHEN_ID = 1;

    @Mock private KitchenRepository repository;
    @Mock private UserRepository userRepository;
    @Mock private KitchenAccessGuard accessGuard;
    @Mock private KitchenCodeGenerator codeGenerator;
    @Mock private KitchenCodeLookupLimiter lookupLimiter;
    @Mock private KitchenDiscoveryLimiter discoveryLimiter;
    @Mock private KitchenAccessRequestStore accessRequestStore;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private KitchenService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).name("Maria").build();
        when(accessGuard.currentUser()).thenReturn(user);
        when(repository.save(any(Kitchen.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Kitchen kitchen() {
        return Kitchen.builder().id(KITCHEN_ID).name("Central").code("AB12CD34").active(true).build();
    }

    // ---------- create ----------

    @Test
    void createGeneratesCodeAndLinksCreatorToTheKitchen() {
        when(accessGuard.currentKitchenId()).thenReturn(null);
        when(codeGenerator.generate()).thenReturn("AB12CD34");
        when(repository.existsByCodeIgnoreCase("AB12CD34")).thenReturn(false);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        Kitchen result = service.create(new CreateKitchenRequest("Central", "Rua A", null, null, null, null));

        assertEquals("AB12CD34", result.getCode());
        assertTrue(result.getActive());
        assertEquals(result, user.getKitchen());
        verify(userRepository).save(user);
    }

    @Test
    void createRetriesWhenGeneratedCodeAlreadyExists() {
        when(accessGuard.currentKitchenId()).thenReturn(null);
        when(codeGenerator.generate()).thenReturn("DUPLICAD", "NOVOCODE");
        when(repository.existsByCodeIgnoreCase("DUPLICAD")).thenReturn(true);
        when(repository.existsByCodeIgnoreCase("NOVOCODE")).thenReturn(false);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        Kitchen result = service.create(new CreateKitchenRequest("Central", null, null, null, null, null));

        assertEquals("NOVOCODE", result.getCode());
        verify(codeGenerator, times(2)).generate();
    }

    @Test
    void createFailsAfterTooManyCodeCollisions() {
        when(accessGuard.currentKitchenId()).thenReturn(null);
        when(codeGenerator.generate()).thenReturn("DUPLICAD");
        when(repository.existsByCodeIgnoreCase("DUPLICAD")).thenReturn(true);

        assertThrows(BusinessRuleException.class, () -> service.create(new CreateKitchenRequest("Central", null, null, null, null, null)));
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsUserAlreadyLinkedToAKitchen() {
        when(accessGuard.currentKitchenId()).thenReturn(KITCHEN_ID);

        assertThrows(BusinessRuleException.class, () -> service.create(new CreateKitchenRequest("Outra", null, null, null, null, null)));
        verify(repository, never()).save(any());
    }

    // ---------- leitura ----------

    @Test
    void findByIdThrowsWhenMissing() {
        when(repository.findById(9)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.findById(9));
    }

    @Test
    void findByIdIsBlockedWithoutAccess() {
        when(repository.findById(KITCHEN_ID)).thenReturn(Optional.of(kitchen()));
        doThrow(new AccessDeniedException("sem acesso")).when(accessGuard).assertAccess(KITCHEN_ID);

        assertThrows(AccessDeniedException.class, () -> service.findById(KITCHEN_ID));
    }

    @Test
    void findByCodeTrimsInputAndRegistersLookupAttempt() {
        when(repository.findByCodeIgnoreCase("AB12CD34")).thenReturn(Optional.of(kitchen()));

        Kitchen result = service.findByCode("  AB12CD34 ");

        assertEquals("AB12CD34", result.getCode());
        verify(lookupLimiter).register(user.getId());
    }

    @Test
    void findByCodeHidesInactiveKitchen() {
        Kitchen inactive = kitchen();
        inactive.setActive(false);
        when(repository.findByCodeIgnoreCase("AB12CD34")).thenReturn(Optional.of(inactive));

        assertThrows(ResourceNotFoundException.class, () -> service.findByCode("AB12CD34"));
    }

    @Test
    void findByCodeFailsForUnknownCode() {
        when(repository.findByCodeIgnoreCase("XXXX")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.findByCode("XXXX"));
    }

    @Test
    void listActiveIsEmptyForUserWithoutKitchen() {
        when(accessGuard.currentKitchenId()).thenReturn(null);

        assertTrue(service.listActive().isEmpty());
    }

    @Test
    void listActiveReturnsOnlyTheUsersOwnActiveKitchen() {
        Kitchen own = kitchen();
        when(accessGuard.currentKitchenId()).thenReturn(KITCHEN_ID);
        when(repository.findById(KITCHEN_ID)).thenReturn(Optional.of(own));

        assertEquals(List.of(own), service.listActive());
    }

    @Test
    void listActiveOmitsInactiveOwnKitchen() {
        Kitchen own = kitchen();
        own.setActive(false);
        when(accessGuard.currentKitchenId()).thenReturn(KITCHEN_ID);
        when(repository.findById(KITCHEN_ID)).thenReturn(Optional.of(own));

        assertTrue(service.listActive().isEmpty());
    }

    // ---------- update / activate / deactivate ----------

    @Test
    void updateChangesOnlyProvidedFields() {
        when(repository.findById(KITCHEN_ID)).thenReturn(Optional.of(kitchen()));

        Kitchen result = service.update(KITCHEN_ID, new UpdateKitchenRequest(null, "Rua Nova, 10", null, null, null, null, null));

        assertEquals("Central", result.getName());
        assertEquals("Rua Nova, 10", result.getAddress());
        verify(accessGuard).assertAccess(KITCHEN_ID);
    }

    @Test
    void updateIsBlockedWithoutAccessAndNeverTouchesTheRepository() {
        doThrow(new AccessDeniedException("sem acesso")).when(accessGuard).assertAccess(KITCHEN_ID);

        assertThrows(AccessDeniedException.class,
                () -> service.update(KITCHEN_ID, new UpdateKitchenRequest("X", null, null, null, null, null, null)));
        verify(repository, never()).save(any());
    }

    @Test
    void deactivateAndActivateToggleTheFlag() {
        Kitchen existing = kitchen();
        when(repository.findById(KITCHEN_ID)).thenReturn(Optional.of(existing));

        service.deactivate(KITCHEN_ID);
        assertFalse(existing.getActive());

        service.activate(KITCHEN_ID);
        assertTrue(existing.getActive());
    }

    @Test
    void activateFailsForUnknownKitchen() {
        when(repository.findById(9)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.activate(9));
    }
}
