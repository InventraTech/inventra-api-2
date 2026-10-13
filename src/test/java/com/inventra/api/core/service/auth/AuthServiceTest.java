package com.inventra.api.core.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import com.inventra.api.core.domain.profile.AccessType;
import com.inventra.api.core.domain.profile.Profile;
import com.inventra.api.core.domain.user.User;
import com.inventra.api.core.service.auth.model.request.LoginRequest;
import com.inventra.api.core.service.auth.model.request.RefreshTokenRequest;
import com.inventra.api.core.service.auth.model.request.RegisterRequest;
import com.inventra.api.core.service.auth.model.response.LoginResponse;
import com.inventra.api.core.service.user.UserUseCase;
import com.inventra.api.infrastructure.exception.TooManyRequestsException;
import com.inventra.api.infrastructure.repository.ProfileRepository;
import com.inventra.api.infrastructure.security.JwtService;
import com.inventra.api.infrastructure.security.LoginAttemptService;
import com.inventra.api.infrastructure.security.UserPrincipal;

import jakarta.servlet.http.HttpServletRequest;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    private static final String EMAIL = "maria@inventra.com";
    private static final String IP = "10.0.0.1";

    @Mock private AuthenticationManager authenticationManager;
    @Mock private JwtService jwtService;
    @Mock private UserUseCase userUseCase;
    @Mock private ProfileRepository profileRepository;
    @Mock private LoginAttemptService loginAttemptService;
    @Mock private HttpServletRequest httpRequest;
    @Mock private RefreshTokenService refreshTokenService;

    @InjectMocks private AuthService service;

    private User user;

    @BeforeEach
    void setUp() {
        Profile profile = Profile.builder().id(2).accessType("estoquista").build();
        user = User.builder().id(UUID.randomUUID()).name("Maria").email(EMAIL).profile(profile).active(true).build();
        when(httpRequest.getRemoteAddr()).thenReturn(IP);
        when(jwtService.generateToken(any(UserPrincipal.class))).thenReturn("jwt-token");
        when(jwtService.getExpirationMs()).thenReturn(3_600_000L);
    }

    private void authenticationSucceeds() {
        Authentication authentication = new UsernamePasswordAuthenticationToken(new UserPrincipal(user), null);
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
    }

    // ---------- login ----------

    @Test
    void loginReturnsTokensAndRegistersTheLogin() {
        authenticationSucceeds();
        when(refreshTokenService.issue(user)).thenReturn(new RefreshTokenService.IssuedRefreshToken("refresh-1", 604800));

        LoginResponse response = service.login(new LoginRequest(EMAIL, "Senha@123"));

        assertEquals("jwt-token", response.token());
        assertEquals("Bearer", response.tokenType());
        assertEquals(3600, response.expiresIn());
        assertEquals("refresh-1", response.refreshToken());
        assertEquals(604800L, response.refreshExpiresIn());
        verify(userUseCase).registerLogin(user.getId());
        verify(loginAttemptService).registerSuccess(EMAIL, IP);
    }

    @Test
    void loginStillWorksWhenRefreshTokenStoreIsDown() {
        authenticationSucceeds();
        when(refreshTokenService.issue(user)).thenThrow(new IllegalStateException("redis fora do ar"));

        LoginResponse response = service.login(new LoginRequest(EMAIL, "Senha@123"));

        assertEquals("jwt-token", response.token());
        assertNull(response.refreshToken());
        assertNull(response.refreshExpiresIn());
    }

    @Test
    void loginRegistersFailureAndRethrowsOnBadCredentials() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

        assertThrows(BadCredentialsException.class, () -> service.login(new LoginRequest(EMAIL, "errada")));

        verify(loginAttemptService).registerFailure(EMAIL, IP);
        verify(loginAttemptService, never()).registerSuccess(any(), any());
        verify(userUseCase, never()).registerLogin(any());
    }

    @Test
    void loginIsBlockedAfterTooManyAttemptsWithoutTouchingAuthentication() {
        when(loginAttemptService.isBlocked(EMAIL, IP)).thenReturn(true);

        assertThrows(TooManyRequestsException.class, () -> service.login(new LoginRequest(EMAIL, "Senha@123")));

        verify(authenticationManager, never()).authenticate(any());
    }

    // ---------- register ----------

    @Test
    void registerUsesExistingProfileForTheAccessType() {
        Profile existing = Profile.builder().id(2).accessType("estoquista").build();
        when(profileRepository.findByAccessType("estoquista")).thenReturn(Optional.of(existing));
        when(userUseCase.registerSelf("Maria", EMAIL, "Senha@123", 2)).thenReturn(user);
        when(refreshTokenService.issue(user)).thenReturn(new RefreshTokenService.IssuedRefreshToken("r", 10));

        LoginResponse response = service.register(new RegisterRequest("Maria", EMAIL, "Senha@123", AccessType.ESTOQUISTA));

        assertEquals("jwt-token", response.token());
        verify(profileRepository, never()).save(any());
    }

    @Test
    void registerCreatesTheProfileOnFirstUse() {
        when(profileRepository.findByAccessType("comprador")).thenReturn(Optional.empty());
        when(profileRepository.save(any(Profile.class))).thenAnswer(inv -> {
            Profile profile = inv.getArgument(0);
            profile.setId(3);
            return profile;
        });
        when(userUseCase.registerSelf("Maria", EMAIL, "Senha@123", 3)).thenReturn(user);

        service.register(new RegisterRequest("Maria", EMAIL, "Senha@123", AccessType.COMPRADOR));

        verify(profileRepository).save(any(Profile.class));
        verify(userUseCase).registerSelf("Maria", EMAIL, "Senha@123", 3);
    }

    // ---------- refresh / logout ----------

    @Test
    void refreshIssuesNewAccessTokenWithRotatedRefreshToken() {
        when(refreshTokenService.rotate("old-refresh")).thenReturn(
                new RefreshTokenService.Rotation(user, new RefreshTokenService.IssuedRefreshToken("new-refresh", 100)));

        LoginResponse response = service.refresh(new RefreshTokenRequest("old-refresh"));

        assertEquals("jwt-token", response.token());
        assertEquals("new-refresh", response.refreshToken());
    }

    @Test
    void logoutRevokesTheRefreshToken() {
        service.logout(new RefreshTokenRequest("refresh-1"));

        verify(refreshTokenService).revoke("refresh-1");
    }
}
