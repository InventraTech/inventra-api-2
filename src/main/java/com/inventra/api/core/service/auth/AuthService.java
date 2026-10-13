package com.inventra.api.core.service.auth;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import com.inventra.api.core.domain.profile.Profile;
import com.inventra.api.core.domain.user.User;
import com.inventra.api.core.service.auth.model.request.LoginRequest;
import com.inventra.api.core.service.auth.model.request.RegisterRequest;
import com.inventra.api.core.service.auth.model.response.LoginResponse;
import com.inventra.api.core.service.user.UserUseCase;
import com.inventra.api.core.service.user.model.request.CreateUserRequest;
import com.inventra.api.core.service.user.model.response.UserResponse;
import com.inventra.api.infrastructure.repository.ProfileRepository;
import com.inventra.api.infrastructure.security.JwtService;
import com.inventra.api.infrastructure.security.UserPrincipal;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService implements AuthUseCase {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserUseCase userUseCase;
    private final ProfileRepository profileRepository;

    @Override
    public LoginResponse login(LoginRequest request) {
        Authentication result = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password()));

        UserPrincipal principal = (UserPrincipal) result.getPrincipal();
        User user = principal.getUser();

        String token = jwtService.generateToken(principal);
        userUseCase.registerLogin(user.getId());

        return new LoginResponse(token, "Bearer", jwtService.getExpirationMs() / 1000, UserResponse.fromEntity(user));
    }

    @Override
    public LoginResponse register(RegisterRequest request) {
        // O enum AccessType define quais perfis são permitidos no auto-cadastro; a linha em tb_profile
        // (necessária porque tb_user.id_profile é NOT NULL) é criada no primeiro uso, se ainda não existir.
        String profileCode = request.accessType().toProfileCode();
        Profile profile = profileRepository.findByAccessType(profileCode)
                .orElseGet(() -> profileRepository.save(Profile.builder()
                        .accessType(profileCode)
                        .description("Criado automaticamente pelo auto-cadastro")
                        .build()));

        User user = userUseCase.create(new CreateUserRequest(
                request.name(),
                request.email(),
                request.password(),
                null,
                profile.getId()
        ));

        UserPrincipal principal = new UserPrincipal(user);
        String token = jwtService.generateToken(principal);

        return new LoginResponse(token, "Bearer", jwtService.getExpirationMs() / 1000, UserResponse.fromEntity(user));
    }
}
