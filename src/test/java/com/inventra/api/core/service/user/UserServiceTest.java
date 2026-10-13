package com.inventra.api.core.service.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
import org.springframework.security.crypto.password.PasswordEncoder;

import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.profile.Profile;
import com.inventra.api.core.domain.user.User;
import com.inventra.api.core.service.user.model.request.ChangePasswordRequest;
import com.inventra.api.core.service.user.model.request.CreateUserRequest;
import com.inventra.api.core.service.user.model.request.UpdateUserRequest;
import com.inventra.api.infrastructure.exception.BusinessRuleException;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.KitchenRepository;
import com.inventra.api.infrastructure.repository.ProfileRepository;
import com.inventra.api.infrastructure.repository.UserRepository;
import com.inventra.api.infrastructure.security.KitchenAccessGuard;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceTest {

    private static final Integer KITCHEN_ID = 1;

    @Mock private UserRepository repository;
    @Mock private KitchenRepository kitchenRepository;
    @Mock private ProfileRepository profileRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private KitchenAccessGuard accessGuard;

    @InjectMocks private UserService service;

    private Kitchen kitchen;
    private Profile supervisorProfile;
    private Profile stockProfile;
    private User supervisor;

    @BeforeEach
    void setUp() {
        kitchen = Kitchen.builder().id(KITCHEN_ID).name("Central").active(true).build();
        supervisorProfile = Profile.builder().id(1).accessType("supervisor").build();
        stockProfile = Profile.builder().id(2).accessType("estoquista").build();
        supervisor = User.builder().id(UUID.randomUUID()).name("Sup").kitchen(kitchen)
                .profile(supervisorProfile).active(true).build();

        // por padrão o usuário logado é um supervisor da cozinha 1
        when(accessGuard.currentUser()).thenReturn(supervisor);
        when(accessGuard.isSupervisor()).thenReturn(true);
        when(accessGuard.currentKitchenId()).thenReturn(KITCHEN_ID);
        when(passwordEncoder.encode(any())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
        when(repository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private User member(Profile profile) {
        return User.builder().id(UUID.randomUUID()).name("Membro").kitchen(kitchen)
                .profile(profile).active(true).build();
    }

    private void asNonSupervisor(User logged) {
        when(accessGuard.currentUser()).thenReturn(logged);
        when(accessGuard.isSupervisor()).thenReturn(false);
    }

    // ---------- create / registerSelf ----------

    @Test
    void createByLoggedSupervisorEncodesPasswordAndNormalizesEmail() {
        when(repository.existsByEmailIgnoreCase("novo@x.com")).thenReturn(false);
        when(profileRepository.findById(2)).thenReturn(Optional.of(stockProfile));
        when(kitchenRepository.findById(KITCHEN_ID)).thenReturn(Optional.of(kitchen));

        User result = service.create(new CreateUserRequest("Novo", "  NOVO@x.com ", "Senha@123", KITCHEN_ID, 2));

        assertEquals("novo@x.com", result.getEmail());
        assertEquals("enc:Senha@123", result.getPasswordHash());
        assertEquals(kitchen, result.getKitchen());
        assertTrue(result.getActive());
        assertNotNull(result.getId());
    }

    @Test
    void createIsBlockedForNonSupervisor() {
        asNonSupervisor(member(stockProfile));

        assertThrows(AccessDeniedException.class,
                () -> service.create(new CreateUserRequest("Novo", "n@x.com", "Senha@123", null, 2)));
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsDuplicateEmail() {
        when(repository.existsByEmailIgnoreCase("dup@x.com")).thenReturn(true);

        assertThrows(BusinessRuleException.class,
                () -> service.create(new CreateUserRequest("Novo", "dup@x.com", "Senha@123", null, 2)));
        verify(repository, never()).save(any());
    }

    @Test
    void createFailsForUnknownProfile() {
        when(profileRepository.findById(99)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.create(new CreateUserRequest("Novo", "n@x.com", "Senha@123", null, 99)));
    }

    @Test
    void createCannotAssignAKitchenOfAnotherSupervisor() {
        doThrow(new AccessDeniedException("sem acesso")).when(accessGuard).assertAccess(7);

        assertThrows(AccessDeniedException.class,
                () -> service.create(new CreateUserRequest("Novo", "n@x.com", "Senha@123", 7, 2)));
        verify(repository, never()).save(any());
    }

    @Test
    void registerSelfCreatesUserWithoutKitchen() {
        when(profileRepository.findById(2)).thenReturn(Optional.of(stockProfile));

        User result = service.registerSelf("Ana", "ana@x.com", "Senha@123", 2);

        assertNull(result.getKitchen());
        assertEquals(stockProfile, result.getProfile());
    }

    // ---------- leitura ----------

    @Test
    void findByIdReturnsOwnUserWithoutSupervisorCheck() {
        User self = member(stockProfile);
        asNonSupervisor(self);
        when(repository.findById(self.getId())).thenReturn(Optional.of(self));

        assertEquals(self, service.findById(self.getId()));
    }

    @Test
    void findByIdBlocksNonSupervisorReadingAnotherUser() {
        User self = member(stockProfile);
        User other = member(stockProfile);
        asNonSupervisor(self);
        when(repository.findById(other.getId())).thenReturn(Optional.of(other));

        assertThrows(AccessDeniedException.class, () -> service.findById(other.getId()));
    }

    @Test
    void findByIdBlocksSupervisorFromAnotherKitchen() {
        User foreign = member(stockProfile);
        foreign.setKitchen(Kitchen.builder().id(99).build());
        when(repository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThrows(AccessDeniedException.class, () -> service.findById(foreign.getId()));
    }

    @Test
    void findByIdThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.findById(id));
    }

    @Test
    void listAllReturnsOnlyOwnKitchenUsersForSupervisor() {
        List<User> users = List.of(supervisor, member(stockProfile));
        when(repository.findByKitchenId(KITCHEN_ID)).thenReturn(users);

        assertEquals(users, service.listAll());
    }

    @Test
    void listAllIsEmptyForSupervisorWithoutKitchen() {
        when(accessGuard.currentKitchenId()).thenReturn(null);

        assertTrue(service.listAll().isEmpty());
    }

    @Test
    void listAllIsBlockedForNonSupervisor() {
        asNonSupervisor(member(stockProfile));

        assertThrows(AccessDeniedException.class, () -> service.listAll());
    }

    // ---------- update ----------

    @Test
    void updateOwnNameDoesNotRequireSupervisor() {
        User self = member(stockProfile);
        asNonSupervisor(self);
        when(repository.findById(self.getId())).thenReturn(Optional.of(self));

        User result = service.update(self.getId(), new UpdateUserRequest("Novo Nome", null, null));

        assertEquals("Novo Nome", result.getName());
    }

    @Test
    void updateProfileOrKitchenRequiresSupervisor() {
        User self = member(stockProfile);
        asNonSupervisor(self);
        when(repository.findById(self.getId())).thenReturn(Optional.of(self));

        assertThrows(AccessDeniedException.class,
                () -> service.update(self.getId(), new UpdateUserRequest(null, null, 1)));
    }

    @Test
    void updateCannotDemoteTheLastActiveSupervisor() {
        when(repository.findById(supervisor.getId())).thenReturn(Optional.of(supervisor));
        when(profileRepository.findById(2)).thenReturn(Optional.of(stockProfile));
        when(repository.countActiveByKitchenAndAccessType(KITCHEN_ID, "supervisor")).thenReturn(1L);

        assertThrows(BusinessRuleException.class,
                () -> service.update(supervisor.getId(), new UpdateUserRequest(null, null, 2)));
        verify(repository, never()).save(any());
    }

    @Test
    void updateCanDemoteSupervisorWhenAnotherOneRemains() {
        when(repository.findById(supervisor.getId())).thenReturn(Optional.of(supervisor));
        when(profileRepository.findById(2)).thenReturn(Optional.of(stockProfile));
        when(repository.countActiveByKitchenAndAccessType(KITCHEN_ID, "supervisor")).thenReturn(2L);

        User result = service.update(supervisor.getId(), new UpdateUserRequest(null, null, 2));

        assertEquals(stockProfile, result.getProfile());
    }

    // ---------- changePassword ----------

    @Test
    void changePasswordEncodesNewPasswordWhenCurrentMatches() {
        User self = member(stockProfile);
        self.setPasswordHash("enc:velha");
        asNonSupervisor(self);
        when(repository.findById(self.getId())).thenReturn(Optional.of(self));
        when(passwordEncoder.matches("velha", "enc:velha")).thenReturn(true);

        service.changePassword(self.getId(), new ChangePasswordRequest("velha", "NovaSenha@1"));

        assertEquals("enc:NovaSenha@1", self.getPasswordHash());
        verify(repository).save(self);
    }

    @Test
    void changePasswordRejectsWrongCurrentPassword() {
        User self = member(stockProfile);
        self.setPasswordHash("enc:velha");
        asNonSupervisor(self);
        when(repository.findById(self.getId())).thenReturn(Optional.of(self));
        when(passwordEncoder.matches("errada", "enc:velha")).thenReturn(false);

        assertThrows(BusinessRuleException.class,
                () -> service.changePassword(self.getId(), new ChangePasswordRequest("errada", "NovaSenha@1")));
        verify(repository, never()).save(any());
    }

    @Test
    void changePasswordOfAnotherUserIsBlockedEvenForSupervisor() {
        User other = member(stockProfile);
        when(repository.findById(other.getId())).thenReturn(Optional.of(other));

        assertThrows(AccessDeniedException.class,
                () -> service.changePassword(other.getId(), new ChangePasswordRequest("a", "NovaSenha@1")));
    }

    // ---------- activate / deactivate ----------

    @Test
    void deactivateAndActivateAnotherUserOfTheSameKitchen() {
        User other = member(stockProfile);
        when(repository.findById(other.getId())).thenReturn(Optional.of(other));

        service.deactivate(other.getId());
        assertFalse(other.getActive());

        service.activate(other.getId());
        assertTrue(other.getActive());
    }

    @Test
    void deactivateOwnAccountIsRejected() {
        when(repository.findById(supervisor.getId())).thenReturn(Optional.of(supervisor));

        assertThrows(BusinessRuleException.class, () -> service.deactivate(supervisor.getId()));
        assertTrue(supervisor.getActive());
    }

    @Test
    void deactivateIsBlockedForNonSupervisor() {
        User self = member(stockProfile);
        User other = member(stockProfile);
        asNonSupervisor(self);
        when(repository.findById(other.getId())).thenReturn(Optional.of(other));

        assertThrows(AccessDeniedException.class, () -> service.deactivate(other.getId()));
    }

    // ---------- misc ----------

    @Test
    void registerLoginStoresLastLogin() {
        User user = member(stockProfile);
        when(repository.findById(user.getId())).thenReturn(Optional.of(user));

        service.registerLogin(user.getId());

        assertNotNull(user.getLastLogin());
    }

    @Test
    void findByEmailThrowsWhenMissing() {
        when(repository.findByEmailIgnoreCase("x@x.com")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.findByEmail("x@x.com"));
    }
}
