package com.inventra.api.core.service.profile;

import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Service;

import com.inventra.api.core.domain.profile.AccessType;
import com.inventra.api.core.domain.profile.Profile;
import com.inventra.api.core.service.profile.model.request.CreateProfileRequest;
import com.inventra.api.core.service.profile.model.request.UpdateProfileRequest;
import com.inventra.api.infrastructure.exception.BusinessRuleException;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.ProfileRepository;
import com.inventra.api.infrastructure.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProfileService implements ProfileUseCase {

    private final ProfileRepository repository;
    private final UserRepository userRepository;

    @Override
    public Profile create(CreateProfileRequest request) {
        assertNotBaseProfileName(request.accessType());
        if (repository.existsByAccessType(request.accessType())) {
            throw new BusinessRuleException("Já existe um perfil com esse tipo de acesso.");
        }

        Profile profile = Profile.builder()
            .accessType(request.accessType())
            .description(request.description())
            .build();

        return repository.save(profile);
    }

    @Override
    public Profile findById(Integer id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Perfil não encontrado."));
    }

    @Override
    public List<Profile> listAll() {
        return repository.findAll();
    }

    @Override
    public Profile update(Integer id, UpdateProfileRequest request) {
        Profile profile = findById(id);

        if (request.accessType() != null && !request.accessType().equals(profile.getAccessType())) {
            assertNotBaseProfile(profile);
            assertNotBaseProfileName(request.accessType());
            if (repository.existsByAccessType(request.accessType())) {
                throw new BusinessRuleException("Já existe um perfil com esse tipo de acesso.");
            }
            profile.setAccessType(request.accessType());
        }
        if (request.description() != null) {
            profile.setDescription(request.description());
        }

        return repository.save(profile);
    }

    @Override
    public void delete(Integer id) {
        Profile profile = findById(id);
        assertNotBaseProfile(profile);

        if (userRepository.existsByProfileId(id)) {
            throw new BusinessRuleException("Não é possível excluir: existem usuários vinculados a esse perfil.");
        }

        repository.delete(profile);
    }

    // supervisor/estoquista/comprador sustentam o RBAC (ROLE_<ACCESS_TYPE>) de todas as cozinhas: renomear ou
    // excluir um deles derrubaria as permissões de todo mundo, e criar outro com o mesmo nome em outra
    // caixa ("SUPERVISOR") duplicaria o papel. Só a descrição deles pode mudar.
    private static boolean isBaseProfileName(String accessType) {
        return accessType != null && Arrays.stream(AccessType.values())
            .anyMatch(type -> type.toProfileCode().equalsIgnoreCase(accessType.trim()));
    }

    private static void assertNotBaseProfile(Profile profile) {
        if (isBaseProfileName(profile.getAccessType())) {
            throw new BusinessRuleException("Os perfis base do sistema (supervisor, estoquista, comprador) não podem ser renomeados nem excluídos.");
        }
    }

    // O nome base exato em minúsculas é permitido (cria o perfil se ainda não existir; o existsByAccessType
    // barra a duplicata); variações de caixa/espaço ("SUPERVISOR", " supervisor") não.
    private static void assertNotBaseProfileName(String accessType) {
        boolean exactBaseCode = Arrays.stream(AccessType.values())
            .anyMatch(type -> type.toProfileCode().equals(accessType));
        if (isBaseProfileName(accessType) && !exactBaseCode) {
            throw new BusinessRuleException("Esse tipo de acesso é reservado aos perfis base do sistema.");
        }
    }
}
