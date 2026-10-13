package com.inventra.api.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.inventra.api.core.domain.profile.Profile;
import com.inventra.api.core.domain.user.User;

class JwtServiceTest {

    private static final String SECRET = "VWOPo/15ObYXo7KSSoRns/P1ahR7e/xXgxTPT4DhRxo=";

    private final JwtService jwtService = new JwtService(SECRET, 3_600_000L);

    private UserPrincipal principal() {
        Profile profile = Profile.builder().id(1).accessType("ADMIN").build();
        User user = User.builder()
                .id(UUID.randomUUID())
                .name("Maria Silva")
                .email("maria.silva@inventra.com")
                .passwordHash("hash")
                .profile(profile)
                .active(true)
                .build();
        return new UserPrincipal(user);
    }

    @Test
    void generatesTokenWithEmailAsSubject() {
        String token = jwtService.generateToken(principal());

        assertThat(jwtService.extractSubject(token)).isEqualTo("maria.silva@inventra.com");
        assertThat(jwtService.isValid(token)).isTrue();
    }

    @Test
    void rejectsTamperedToken() {
        String token = jwtService.generateToken(principal());
        // Troca o 1º caractere da assinatura: o último só carrega 4 bits úteis em Base64URL,
        // então trocá-lo às vezes não altera a assinatura e o token continua válido.
        int signatureStart = token.lastIndexOf('.') + 1;
        char first = token.charAt(signatureStart);
        String tampered = token.substring(0, signatureStart) + (first == 'A' ? 'B' : 'A') + token.substring(signatureStart + 1);

        assertThat(jwtService.isValid(tampered)).isFalse();
    }

    @Test
    void rejectsGarbageToken() {
        assertThat(jwtService.isValid("not-a-jwt")).isFalse();
    }

    @Test
    void rejectsExpiredToken() {
        JwtService expiredIssuer = new JwtService(SECRET, -1_000L);
        String expiredToken = expiredIssuer.generateToken(principal());

        assertThat(jwtService.isValid(expiredToken)).isFalse();
    }
}
