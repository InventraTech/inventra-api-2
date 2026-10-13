package com.inventra.api.core.service.user;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

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

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserService implements UserUseCase {

    private final UserRepository repository;
    private final KitchenRepository kitchenRepository;
    private final ProfileRepository profileRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public User create(CreateUserRequest request) {
        if (repository.existsByEmail(request.email())) {
            throw new BusinessRuleException("Já existe um usuário com esse e-mail.");
        }

        Profile profile = profileRepository.findById(request.profileId())
            .orElseThrow(() -> new ResourceNotFoundException("Perfil não encontrado."));

        Kitchen kitchen = null;
        if (request.kitchenId() != null) {
            kitchen = kitchenRepository.findById(request.kitchenId())
                .orElseThrow(() -> new ResourceNotFoundException("Cozinha não encontrada."));
        }

        User user = User.builder()
            .id(UUID.randomUUID())
            .name(request.name())
            .email(request.email())
            .passwordHash(passwordEncoder.encode(request.password()))
            .kitchen(kitchen)
            .profile(profile)
            .active(true)
            .build();

        return repository.save(user);
    }

    @Override
    public User findById(UUID id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado."));
    }

    @Override
    public User findByEmail(String email) {
        return repository.findByEmail(email)
            .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado."));
    }

    @Override
    public List<User> listAll(){
        return repository.findAll();
    }

    @Override
    public User update(UUID id, UpdateUserRequest request) {
        User currentUser = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado."));

        if (request.name() != null) {
            currentUser.setName(request.name());
        }
        if (request.kitchenId() != null) {
            Kitchen kitchen = kitchenRepository.findById(request.kitchenId())
                .orElseThrow(() -> new ResourceNotFoundException("Cozinha não encontrada."));
            currentUser.setKitchen(kitchen);
        }
        if (request.profileId() != null) {
            Profile profile = profileRepository.findById(request.profileId())
                .orElseThrow(() -> new ResourceNotFoundException("Perfil não encontrado."));
            currentUser.setProfile(profile);
        }

        return repository.save(currentUser);
    }

    @Override
    public void changePassword(UUID id, ChangePasswordRequest request) {
        User user = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado."));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessRuleException("Senha atual incorreta.");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        repository.save(user);
    }

    @Override
    public void activate(UUID id) {
        User user = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado."));
        user.setActive(true);
        repository.save(user);
    }

    @Override
    public void deactivate(UUID id) {
        User user = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado."));
        user.setActive(false);
        repository.save(user);
    }

    @Override
    public void registerLogin(UUID id) {
        User user = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado."));
        user.setLastLogin(LocalDateTime.now());
        repository.save(user);
    }
}
