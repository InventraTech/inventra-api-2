package com.inventra.api.core.service.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

import com.inventra.api.core.domain.user.User;
import com.inventra.api.infrastructure.repository.UserRepository;
import com.inventra.api.infrastructure.security.JwtService;

// Refresh token opaco com rotação: cada renovação troca o token por um novo da mesma "família". Se um token
// já trocado aparecer de novo (fora de uma pequena tolerância para chamadas simultâneas do app), a família
// inteira é revogada, porque o token provavelmente vazou. Trocar a senha ou desativar a conta também
// impede a renovação.
@Service
public class RefreshTokenService {

    static final Duration REUSE_GRACE = Duration.ofSeconds(10);
    private static final String INVALID = "Refresh token inválido ou expirado.";

    private final RefreshTokenStore store;
    private final UserRepository userRepository;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenStore store, UserRepository userRepository,
                               @Value("${jwt.refresh-expiration-ms:2592000000}") long refreshExpirationMs) {
        this.store = store;
        this.userRepository = userRepository;
        this.ttl = Duration.ofMillis(refreshExpirationMs);
    }

    public record IssuedRefreshToken(String token, long expiresInSeconds) {
    }

    public record Rotation(User user, IssuedRefreshToken refreshToken) {
    }

    // Novo login: abre uma família nova.
    public IssuedRefreshToken issue(User user) {
        return create(user, UUID.randomUUID().toString());
    }

    public Rotation rotate(String rawToken) {
        String hash = hash(rawToken);
        RefreshTokenData data = store.find(hash).orElseThrow(() -> new BadCredentialsException(INVALID));

        Instant now = Instant.now();
        if (data.usedAt() != null && Duration.between(data.usedAt(), now).compareTo(REUSE_GRACE) > 0) {
            store.revokeFamily(data.familyId());
            throw new BadCredentialsException(INVALID);
        }

        User user = userRepository.findById(UUID.fromString(data.userId())).orElse(null);
        if (user == null || !Boolean.TRUE.equals(user.getActive())
                || !JwtService.passwordFingerprint(user.getPasswordHash()).equals(data.passwordFingerprint())) {
            store.revokeFamily(data.familyId());
            throw new BadCredentialsException(INVALID);
        }

        if (data.usedAt() == null) {
            store.markUsed(hash, now);
        }
        return new Rotation(user, create(user, data.familyId()));
    }

    // Logout: revoga a família do token informado. Token desconhecido não é erro (logout é idempotente).
    public void revoke(String rawToken) {
        store.find(hash(rawToken)).ifPresent(data -> store.revokeFamily(data.familyId()));
    }

    private IssuedRefreshToken create(User user, String familyId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        store.save(hash(token),
                new RefreshTokenData(user.getId().toString(), familyId,
                        JwtService.passwordFingerprint(user.getPasswordHash()), null),
                ttl);
        return new IssuedRefreshToken(token, ttl.toSeconds());
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 indisponível", ex);
        }
    }
}
