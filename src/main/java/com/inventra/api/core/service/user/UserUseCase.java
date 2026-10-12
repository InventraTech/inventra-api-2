package com.inventra.api.core.service.user;

import com.inventra.api.core.service.user.model.request.ChangePasswordRequest;
import com.inventra.api.core.service.user.model.request.CreateUserRequest;
import com.inventra.api.core.service.user.model.request.UpdateUserRequest;
import com.inventra.api.core.domain.user.User;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserUseCase {

    User create(CreateUserRequest request);

    // auto-cadastro (/api/auth/register): sem usuário logado e sem cozinha
    User registerSelf(String name, String email, String password, Integer profileId);

    // login com Google: conta já vinculada ao uid ou, se não houver, a conta com o mesmo e-mail (que passa a
    // ficar vinculada). Vazio = primeiro acesso.
    Optional<User> findGoogleAccount(String firebaseUid, String email);

    // primeiro acesso com Google: conta sem senha e sem cozinha
    User registerWithGoogle(String name, String email, String firebaseUid, Integer profileId);

    User findById(UUID id);

    // usuário do token, recarregado do banco
    User findCurrent();

    User findByEmail(String email);

    List<User> listAll();

    User update(UUID id, UpdateUserRequest request);

    void changePassword(UUID id, ChangePasswordRequest request);

    // já existe active, não precisa hard delete
    void activate(UUID id);

    void deactivate(UUID id);

    // atualiza lastLogin, chamado pelo fluxo de auth
    void registerLogin(UUID id);
}
