package com.inventra.api.core.service.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.inventra.api.core.domain.profile.Profile;
import com.inventra.api.core.service.profile.model.request.CreateProfileRequest;
import com.inventra.api.core.service.profile.model.request.UpdateProfileRequest;
import com.inventra.api.infrastructure.exception.BusinessRuleException;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.ProfileRepository;
import com.inventra.api.infrastructure.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    @Mock private ProfileRepository repository;
    @Mock private UserRepository userRepository;

    @InjectMocks private ProfileService service;

    private static Profile custom() {
        return Profile.builder().id(10).accessType("auditor").description("Somente leitura").build();
    }

    private static Profile base() {
        return Profile.builder().id(1).accessType("supervisor").description("Base").build();
    }

    @Test
    void createSavesCustomProfile() {
        when(repository.existsByAccessType("auditor")).thenReturn(false);
        when(repository.save(any(Profile.class))).thenAnswer(inv -> inv.getArgument(0));

        Profile result = service.create(new CreateProfileRequest("auditor", "Somente leitura"));

        assertEquals("auditor", result.getAccessType());
    }

    @Test
    void createRejectsDuplicateAccessType() {
        when(repository.existsByAccessType("auditor")).thenReturn(true);

        assertThrows(BusinessRuleException.class,
                () -> service.create(new CreateProfileRequest("auditor", null)));
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsReservedNameInOtherCase() {
        assertThrows(BusinessRuleException.class,
                () -> service.create(new CreateProfileRequest("SUPERVISOR", null)));
        verify(repository, never()).save(any());
    }

    @Test
    void findByIdThrowsWhenMissing() {
        when(repository.findById(9)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.findById(9));
    }

    @Test
    void updateCustomProfileCanBeRenamed() {
        when(repository.findById(10)).thenReturn(Optional.of(custom()));
        when(repository.existsByAccessType("fiscal")).thenReturn(false);
        when(repository.save(any(Profile.class))).thenAnswer(inv -> inv.getArgument(0));

        Profile result = service.update(10, new UpdateProfileRequest("fiscal", null));

        assertEquals("fiscal", result.getAccessType());
    }

    @Test
    void updateBaseProfileCannotBeRenamed() {
        when(repository.findById(1)).thenReturn(Optional.of(base()));

        assertThrows(BusinessRuleException.class,
                () -> service.update(1, new UpdateProfileRequest("chefe", null)));
        verify(repository, never()).save(any());
    }

    @Test
    void updateBaseProfileDescriptionIsAllowed() {
        when(repository.findById(1)).thenReturn(Optional.of(base()));
        when(repository.save(any(Profile.class))).thenAnswer(inv -> inv.getArgument(0));

        Profile result = service.update(1, new UpdateProfileRequest(null, "Nova descrição"));

        assertEquals("supervisor", result.getAccessType());
        assertEquals("Nova descrição", result.getDescription());
    }

    @Test
    void deleteRemovesCustomProfileWithoutUsers() {
        Profile existing = custom();
        when(repository.findById(10)).thenReturn(Optional.of(existing));
        when(userRepository.existsByProfileId(10)).thenReturn(false);

        service.delete(10);

        verify(repository).delete(existing);
    }

    @Test
    void deleteIsBlockedForBaseProfile() {
        when(repository.findById(1)).thenReturn(Optional.of(base()));

        assertThrows(BusinessRuleException.class, () -> service.delete(1));
        verify(repository, never()).delete(any());
    }

    @Test
    void deleteIsBlockedWhenUsersAreLinked() {
        when(repository.findById(10)).thenReturn(Optional.of(custom()));
        when(userRepository.existsByProfileId(10)).thenReturn(true);

        assertThrows(BusinessRuleException.class, () -> service.delete(10));
        verify(repository, never()).delete(any());
    }
}
