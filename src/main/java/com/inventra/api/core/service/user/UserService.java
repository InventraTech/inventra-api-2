package com.inventra.api.core.service.user;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.inventra.api.core.service.user.model.request.ChangePasswordRequest;
import com.inventra.api.core.service.user.model.request.CreateUserRequest;
import com.inventra.api.core.service.user.model.request.UpdateUserRequest;
import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.profile.Profile;
import com.inventra.api.core.domain.user.User;
import com.inventra.api.infrastructure.exception.BusinessRuleException;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.KitchenRepository;
import com.inventra.api.infrastructure.repository.ProfileRepository;
import com.inventra.api.infrastructure.repository.UserRepository;
import com.inventra.api.infrastructure.security.KitchenAccessGuard;
import com.inventra.api.infrastructure.security.Roles;

import lombok.RequiredArgsConstructor;

// Gestão de usuários é do supervisor, e só dentro da própria cozinha. Usuário sem cozinha não é "de ninguém":
// o supervisor não o enxerga nem o puxa. A entrada numa cozinha é por pedido do próprio usuário, que o
// supervisor aprova (KitchenAccessRequestService). Ninguém vincula usuário a outra cozinha.
@Service
@RequiredArgsConstructor
public class UserService implements UserUseCase {

    private final UserRepository repository;
    private final KitchenRepository kitchenRepository;
    private final ProfileRepository profileRepository;
    private final PasswordEncoder passwordEncoder;
    private final KitchenAccessGuard accessGuard;

    @Override
    public User create(CreateUserRequest request) {
        if (!accessGuard.isSupervisor()) {
            throw new AccessDeniedException("Somente supervisor pode criar usuários.");
        }
        Kitchen kitchen = request.kitchenId() != null ? resolveAssignableKitchen(request.kitchenId()) : null;
        return save(request.name(), request.email(), request.password(), null, kitchen, request.profileId());
    }

    @Override
    public User registerSelf(String name, String email, String password, Integer profileId) {
        return save(name, email, password, null, null, profileId);
    }

    @Override
    public Optional<User> findGoogleAccount(String firebaseUid, String email) {
        Optional<User> linked = repository.findByFirebaseUidWithProfile(firebaseUid);
        if (linked.isPresent()) {
            return linked;
        }
        // mesma regra do login por senha para e-mails duplicados só na caixa: vale a correspondência exata
        List<User> candidates = repository.findAllByEmailWithProfile(email);
        Optional<User> byEmail = candidates.size() == 1
            ? Optional.of(candidates.get(0))
            : candidates.stream().filter(user -> user.getEmail().equals(email)).findFirst();
        byEmail.ifPresent(user -> {
            if (user.getFirebaseUid() != null) {
                throw new BusinessRuleException("Este e-mail já está vinculado a outra conta Google.");
            }
            user.setFirebaseUid(firebaseUid);
            repository.save(user);
        });
        return byEmail;
    }

    @Override
    public User registerWithGoogle(String name, String email, String firebaseUid, Integer profileId) {
        return save(name, email, null, firebaseUid, null, profileId);
    }

    @Override
    public User findById(UUID id) {
        User user = load(id);
        if (!isCurrentUser(user)) {
            assertCanManage(user);
        }
        return user;
    }

    @Override
    public User findCurrent() {
        return load(accessGuard.currentUser().getId());
    }

    @Override
    public User findByEmail(String email) {
        return repository.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado."));
    }

    @Override
    public List<User> listAll() {
        if (!accessGuard.isSupervisor()) {
            throw new AccessDeniedException("Somente supervisor pode listar usuários.");
        }
        Integer ownKitchenId = accessGuard.currentKitchenId();
        return ownKitchenId == null ? List.of() : repository.findByKitchenId(ownKitchenId);
    }

    @Override
    public User update(UUID id, UpdateUserRequest request) {
        User currentUser = load(id);
        if (!isCurrentUser(currentUser)) {
            assertCanManage(currentUser);
        }
        if ((request.kitchenId() != null || request.profileId() != null) && !accessGuard.isSupervisor()) {
            throw new AccessDeniedException("Somente supervisor pode alterar cozinha ou perfil.");
        }

        if (request.name() != null) {
            currentUser.setName(request.name());
        }
        if (request.kitchenId() != null) {
            currentUser.setKitchen(resolveAssignableKitchen(request.kitchenId()));
        }
        if (request.profileId() != null) {
            Profile profile = profileRepository.findById(request.profileId())
                .orElseThrow(() -> new ResourceNotFoundException("Perfil não encontrado."));
            assertNotRemovingLastSupervisor(currentUser, profile);
            currentUser.setProfile(profile);
        }
        return repository.save(currentUser);
    }

    @Override
    public void changePassword(UUID id, ChangePasswordRequest request) {
        User user = load(id);
        if (!isCurrentUser(user)) {
            throw new AccessDeniedException("Só é possível alterar a própria senha.");
        }
        // conta criada pelo Google não tem senha: a primeira troca cria uma, sem conferir a atual
        if (user.getPasswordHash() != null
                && (request.currentPassword() == null
                    || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash()))) {
            throw new BusinessRuleException("Senha atual incorreta.");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        repository.save(user);
    }

    @Override
    public void activate(UUID id) {
        User user = load(id);
        assertCanManage(user);
        user.setActive(true);
        repository.save(user);
    }

    @Override
    public void deactivate(UUID id) {
        User user = load(id);
        assertCanManage(user);
        if (isCurrentUser(user)) {
            throw new BusinessRuleException("Não é possível desativar a própria conta.");
        }
        user.setActive(false);
        repository.save(user);
    }

    @Override
    public void registerLogin(UUID id) {
        User user = load(id);
        user.setLastLogin(LocalDateTime.now());
        repository.save(user);
    }

    private User save(String name, String email, String password, String firebaseUid, Kitchen kitchen,
                      Integer profileId) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (repository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new BusinessRuleException("Já existe um usuário com esse e-mail.");
        }
        Profile profile = profileRepository.findById(profileId)
            .orElseThrow(() -> new ResourceNotFoundException("Perfil não encontrado."));

        User user = User.builder()
            .id(UUID.randomUUID())
            .name(name)
            .email(normalizedEmail)
            .passwordHash(password == null ? null : passwordEncoder.encode(password))
            .firebaseUid(firebaseUid)
            .kitchen(kitchen)
            .profile(profile)
            .active(true)
            .build();
        return repository.save(user);
    }

    private User load(UUID id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado."));
    }

    private boolean isCurrentUser(User user) {
        return user.getId().equals(accessGuard.currentUser().getId());
    }

    // Supervisor gerencia só usuários da própria cozinha.
    private void assertCanManage(User target) {
        Integer ownKitchenId = accessGuard.currentKitchenId();
        boolean inScope = target.getKitchen() != null
            && ownKitchenId != null && ownKitchenId.equals(target.getKitchen().getId());
        if (!accessGuard.isSupervisor() || !inScope) {
            throw new AccessDeniedException("Você não tem permissão para gerenciar esse usuário.");
        }
    }

    // A cozinha não pode ficar sem nenhum supervisor ativo: senão ninguém mais consegue gerenciá-la.
    private void assertNotRemovingLastSupervisor(User target, Profile newProfile) {
        boolean wasSupervisor = Roles.SUPERVISOR_PROFILE.equalsIgnoreCase(target.getProfile().getAccessType());
        boolean staysSupervisor = Roles.SUPERVISOR_PROFILE.equalsIgnoreCase(newProfile.getAccessType());
        if (wasSupervisor && !staysSupervisor && target.getKitchen() != null && Boolean.TRUE.equals(target.getActive())
                && repository.countActiveByKitchenAndAccessType(target.getKitchen().getId(), Roles.SUPERVISOR_PROFILE) <= 1) {
            throw new BusinessRuleException("A cozinha precisa de pelo menos um supervisor ativo.");
        }
    }

    // Só dá pra vincular usuário à cozinha do próprio supervisor.
    private Kitchen resolveAssignableKitchen(Integer kitchenId) {
        accessGuard.assertAccess(kitchenId);
        return kitchenRepository.findById(kitchenId)
            .orElseThrow(() -> new ResourceNotFoundException("Cozinha não encontrada."));
    }
}
