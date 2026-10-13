package com.inventra.api.core.service.auth;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import com.inventra.api.core.domain.profile.AccessType;
import com.inventra.api.core.domain.profile.Profile;
import com.inventra.api.core.domain.user.User;
import com.inventra.api.core.service.auth.model.request.GoogleLoginRequest;
import com.inventra.api.core.service.auth.model.request.LoginRequest;
import com.inventra.api.core.service.auth.model.request.RefreshTokenRequest;
import com.inventra.api.core.service.auth.model.request.RegisterRequest;
import com.inventra.api.core.service.auth.model.response.LoginResponse;
import com.inventra.api.core.service.user.UserUseCase;
import com.inventra.api.core.service.user.model.response.UserResponse;
import com.inventra.api.infrastructure.exception.ApiProblemException;
import com.inventra.api.infrastructure.exception.TooManyRequestsException;
import com.inventra.api.infrastructure.repository.ProfileRepository;
import com.inventra.api.infrastructure.security.JwtService;
import com.inventra.api.infrastructure.security.LoginAttemptService;
import com.inventra.api.infrastructure.security.UserPrincipal;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService implements AuthUseCase {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserUseCase userUseCase;
    private final ProfileRepository profileRepository;
    private final LoginAttemptService loginAttemptService;
    private final HttpServletRequest httpRequest;
    private final RefreshTokenService refreshTokenService;
    private final GoogleIdentityVerifier googleIdentityVerifier;

    @Override
    public LoginResponse login(LoginRequest request) {
        String ip = httpRequest.getRemoteAddr();
        if (loginAttemptService.isBlocked(request.email(), ip)) {
            throw new TooManyRequestsException("Muitas tentativas de login. Aguarde alguns minutos e tente novamente.");
        }

        Authentication result;
        try {
            result = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.password()));
        } catch (BadCredentialsException ex) {
            loginAttemptService.registerFailure(request.email(), ip);
            throw ex;
        }
        loginAttemptService.registerSuccess(request.email(), ip);

        UserPrincipal principal = (UserPrincipal) result.getPrincipal();
        User user = principal.getUser();

        String token = jwtService.generateToken(principal);
        userUseCase.registerLogin(user.getId());

        return buildResponse(user, token, issueRefreshToken(user));
    }

    @Override
    public LoginResponse register(RegisterRequest request) {
        Profile profile = resolveProfile(request.accessType());

        User user = userUseCase.registerSelf(request.name(), request.email(), request.password(), profile.getId());

        UserPrincipal principal = new UserPrincipal(user);
        String token = jwtService.generateToken(principal);

        return buildResponse(user, token, issueRefreshToken(user));
    }

    @Override
    public LoginResponse loginWithGoogle(GoogleLoginRequest request) {
        GoogleIdentity identity = googleIdentityVerifier.verify(request.idToken());
        if (identity.email() == null || identity.email().isBlank()) {
            throw new ApiProblemException(HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN",
                    "Token do Google inválido ou expirado.");
        }
        // Vincular pelo e-mail só é seguro porque o Google confirma que a pessoa é dona dele.
        if (!identity.emailVerified()) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED",
                    "O e-mail dessa conta Google ainda não foi verificado.");
        }

        User user = userUseCase.findGoogleAccount(identity.uid(), identity.email())
                .orElseGet(() -> registerGoogleAccount(identity, request.accessType()));
        if (!Boolean.TRUE.equals(user.getActive())) {
            throw new DisabledException("Conta desativada.");
        }

        userUseCase.registerLogin(user.getId());
        String token = jwtService.generateToken(new UserPrincipal(user));
        return buildResponse(user, token, issueRefreshToken(user));
    }

    // Primeiro acesso: o Google não sabe o cargo da pessoa, então sem accessType o app precisa perguntar.
    private User registerGoogleAccount(GoogleIdentity identity, AccessType accessType) {
        if (accessType == null) {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("email", identity.email());
            if (identity.name() != null && !identity.name().isBlank()) {
                properties.put("name", identity.name());
            }
            throw new ApiProblemException(HttpStatus.CONFLICT, "REGISTRATION_REQUIRED",
                    "Conta ainda não cadastrada: informe o tipo de acesso para concluir o cadastro.", properties);
        }
        Profile profile = resolveProfile(accessType);
        return userUseCase.registerWithGoogle(displayName(identity), identity.email(), identity.uid(), profile.getId());
    }

    private static String displayName(GoogleIdentity identity) {
        String name = identity.name() == null || identity.name().isBlank()
                ? identity.email().substring(0, Math.max(1, identity.email().indexOf('@')))
                : identity.name().trim();
        return name.length() > 120 ? name.substring(0, 120) : name;
    }

    // O enum AccessType define quais perfis são permitidos no auto-cadastro; a linha em tb_profile
    // (necessária porque tb_user.id_profile é NOT NULL) é criada no primeiro uso, se ainda não existir.
    private Profile resolveProfile(AccessType accessType) {
        String profileCode = accessType.toProfileCode();
        return profileRepository.findByAccessType(profileCode)
                .orElseGet(() -> profileRepository.save(Profile.builder()
                        .accessType(profileCode)
                        .description("Criado automaticamente pelo auto-cadastro")
                        .build()));
    }

    @Override
    public LoginResponse refresh(RefreshTokenRequest request) {
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(request.refreshToken());
        User user = rotation.user();
        String token = jwtService.generateToken(new UserPrincipal(user));
        return buildResponse(user, token, rotation.refreshToken());
    }

    @Override
    public void logout(RefreshTokenRequest request) {
        refreshTokenService.revoke(request.refreshToken());
    }

    // Se o Redis estiver fora do ar, o login ainda funciona: sem refresh token, o app renova no próximo login.
    private RefreshTokenService.IssuedRefreshToken issueRefreshToken(User user) {
        try {
            return refreshTokenService.issue(user);
        } catch (RuntimeException ex) {
            log.warn("Não foi possível emitir o refresh token do usuário {}: {}", user.getId(), ex.toString());
            return null;
        }
    }

    private LoginResponse buildResponse(User user, String token, RefreshTokenService.IssuedRefreshToken refresh) {
        return new LoginResponse(token, "Bearer", jwtService.getExpirationMs() / 1000,
                refresh == null ? null : refresh.token(),
                refresh == null ? null : refresh.expiresInSeconds(),
                UserResponse.fromEntity(user));
    }
}
