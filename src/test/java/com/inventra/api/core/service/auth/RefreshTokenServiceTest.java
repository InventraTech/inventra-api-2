package com.inventra.api.core.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

import com.inventra.api.core.domain.user.User;
import com.inventra.api.infrastructure.redis.InMemoryRefreshTokenStore;
import com.inventra.api.infrastructure.repository.UserRepository;

class RefreshTokenServiceTest {

    private InMemoryRefreshTokenStore store;
    private UserRepository userRepository;
    private RefreshTokenService service;
    private User user;

    @BeforeEach
    void setUp() {
        store = new InMemoryRefreshTokenStore();
        userRepository = mock(UserRepository.class);
        user = User.builder().id(UUID.randomUUID()).passwordHash("hash-da-senha").active(true).build();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        service = new RefreshTokenService(store, userRepository, 3_600_000L);
    }

    @Test
    void rotateReturnsANewTokenForTheSameUser() {
        String first = service.issue(user).token();

        RefreshTokenService.Rotation rotation = service.rotate(first);

        assertThat(rotation.user()).isSameAs(user);
        assertThat(rotation.refreshToken().token()).isNotEqualTo(first);
        assertThat(rotation.refreshToken().expiresInSeconds()).isEqualTo(3600);
    }

    @Test
    void tokenIsOnlyStoredAsHash() {
        String token = service.issue(user).token();

        assertThat(store.find(token)).isEmpty();
        assertThat(store.find(RefreshTokenService.hash(token))).isPresent();
    }

    @Test
    void unknownTokenIsRejected() {
        assertThatThrownBy(() -> service.rotate("token-que-nao-existe")).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void reusingATokenRightAfterRotationIsTolerated() {
        String first = service.issue(user).token();
        service.rotate(first);

        // duas chamadas simultâneas do app com o mesmo token expirado não derrubam a sessão
        assertThat(service.rotate(first).refreshToken().token()).isNotEqualTo(first);
    }

    @Test
    void reusingATokenAfterTheToleranceRevokesTheWholeFamily() {
        String first = service.issue(user).token();
        String second = service.rotate(first).refreshToken().token();
        // o primeiro token foi trocado há 60 s, bem depois da tolerância
        store.forceUsedAt(RefreshTokenService.hash(first), Instant.now().minusSeconds(60));

        assertThatThrownBy(() -> service.rotate(first)).isInstanceOf(BadCredentialsException.class);
        // o token mais novo da mesma família também foi revogado
        assertThatThrownBy(() -> service.rotate(second)).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void changingThePasswordRevokesRefresh() {
        String token = service.issue(user).token();
        user.setPasswordHash("outra-senha");

        assertThatThrownBy(() -> service.rotate(token)).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void deactivatedUserCannotRefresh() {
        String token = service.issue(user).token();
        user.setActive(false);

        assertThatThrownBy(() -> service.rotate(token)).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void revokeInvalidatesTheFamilyAndIsIdempotent() {
        String token = service.issue(user).token();

        service.revoke(token);
        service.revoke(token);
        service.revoke("desconhecido");

        assertThatThrownBy(() -> service.rotate(token)).isInstanceOf(BadCredentialsException.class);
    }
}
