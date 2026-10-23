package com.inventra.api.core.service.passwordreset;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.inventra.api.core.domain.user.User;
import com.inventra.api.core.service.passwordreset.model.request.ForgotPasswordRequest;
import com.inventra.api.core.service.passwordreset.model.request.ResetPasswordRequest;
import com.inventra.api.core.service.passwordreset.model.request.VerifyResetCodeRequest;
import com.inventra.api.core.service.passwordreset.model.response.ResetTokenResponse;
import com.inventra.api.infrastructure.exception.ApiProblemException;
import com.inventra.api.infrastructure.repository.UserRepository;
import com.inventra.api.infrastructure.security.JwtService;

import lombok.extern.slf4j.Slf4j;

// Redefinição de senha por e-mail. Código, token e e-mail só vão para o store como hash SHA-256. O token
// guarda a impressão da senha: depois da troca ele não vale mais, e a troca também derruba os access e
// refresh tokens já emitidos (mesma impressão). Conta criada pelo Google, sem senha, ganha uma por aqui,
// como no PATCH /api/users/{id}/password.
@Service
@Slf4j
public class PasswordResetService implements PasswordResetUseCase {

    static final Duration REQUEST_WINDOW = Duration.ofHours(1);
    private static final String INVALID_CODE = "Código inválido ou expirado. Confira o código ou peça um novo.";
    private static final String INVALID_TOKEN = "Link ou token de redefinição inválido ou expirado. Peça um novo.";

    private final PasswordResetStore store;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetMailer mailer;
    private final Duration ttl;
    private final int maxAttempts;
    private final int maxRequestsPerHour;
    private final String webUrl;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetService(PasswordResetStore store, UserRepository userRepository,
                                PasswordEncoder passwordEncoder, PasswordResetMailer mailer,
                                @Value("${app.password-reset.ttl:15m}") Duration ttl,
                                @Value("${app.password-reset.max-attempts:5}") int maxAttempts,
                                @Value("${app.password-reset.max-requests-per-hour:5}") int maxRequestsPerHour,
                                @Value("${app.password-reset.web-url:http://localhost:3000/redefinir-senha}") String webUrl) {
        this.store = store;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailer = mailer;
        this.ttl = ttl;
        this.maxAttempts = maxAttempts;
        this.maxRequestsPerHour = maxRequestsPerHour;
        this.webUrl = webUrl;
    }

    @Override
    public void forgot(ForgotPasswordRequest request) {
        String email = normalize(request.email());
        if (store.countRequest(hash(email), REQUEST_WINDOW) > maxRequestsPerHour) {
            log.info("Pedido de redefinição de senha ignorado: limite por hora atingido");
            return;
        }
        Optional<User> found = userRepository.findByEmailIgnoreCase(email)
                .filter(user -> Boolean.TRUE.equals(user.getActive()));
        if (found.isEmpty()) {
            return;
        }
        User user = found.get();

        String code = "%06d".formatted(random.nextInt(1_000_000));
        store.saveCode(user.getId(), codeHash(user.getId(), code), ttl);

        String linkToken = newToken();
        store.saveToken(hash(linkToken), tokenData(user), ttl);

        mailer.send(user.getEmail(), user.getName(), code, webUrl + "?token=" + linkToken, ttl);
    }

    @Override
    public ResetTokenResponse verify(VerifyResetCodeRequest request) {
        User user = userRepository.findByEmailIgnoreCase(normalize(request.email()))
                .filter(found -> Boolean.TRUE.equals(found.getActive()))
                .orElseThrow(PasswordResetService::invalidCode);

        PasswordResetStore.CodeCheck check = store.checkCode(user.getId(), codeHash(user.getId(), request.code()),
                maxAttempts);
        if (check != PasswordResetStore.CodeCheck.MATCH) {
            throw invalidCode();
        }

        String token = newToken();
        store.saveToken(hash(token), tokenData(user), ttl);
        return new ResetTokenResponse(token, ttl.toSeconds());
    }

    @Override
    public void reset(ResetPasswordRequest request) {
        ResetTokenData data = store.consumeToken(hash(request.resetToken()))
                .orElseThrow(PasswordResetService::invalidToken);

        User user = userRepository.findById(UUID.fromString(data.userId()))
                .filter(found -> Boolean.TRUE.equals(found.getActive()))
                .filter(found -> JwtService.passwordFingerprint(found.getPasswordHash())
                        .equals(data.passwordFingerprint()))
                .orElseThrow(PasswordResetService::invalidToken);

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        store.deleteCode(user.getId());
    }

    private static ResetTokenData tokenData(User user) {
        return new ResetTokenData(user.getId().toString(), JwtService.passwordFingerprint(user.getPasswordHash()));
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ApiProblemException invalidCode() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_RESET_CODE", INVALID_CODE);
    }

    private static ApiProblemException invalidToken() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_RESET_TOKEN", INVALID_TOKEN);
    }

    static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    static String codeHash(UUID userId, String code) {
        return hash(userId + ":" + code);
    }

    static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 indisponível", ex);
        }
    }
}
