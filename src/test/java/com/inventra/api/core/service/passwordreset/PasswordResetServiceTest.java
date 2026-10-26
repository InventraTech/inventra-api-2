package com.inventra.api.core.service.passwordreset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.inventra.api.core.domain.user.User;
import com.inventra.api.core.service.passwordreset.model.request.ForgotPasswordRequest;
import com.inventra.api.core.service.passwordreset.model.request.ResetPasswordRequest;
import com.inventra.api.core.service.passwordreset.model.request.VerifyResetCodeRequest;
import com.inventra.api.core.service.passwordreset.model.response.ResetTokenResponse;
import com.inventra.api.infrastructure.exception.ApiProblemException;
import com.inventra.api.infrastructure.redis.InMemoryPasswordResetStore;
import com.inventra.api.infrastructure.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PasswordResetServiceTest {

    private static final String EMAIL = "maria@inventra.com";
    private static final String WEB_URL = "https://web.inventra/redefinir-senha";
    private static final Duration TTL = Duration.ofMinutes(15);

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private PasswordResetMailer mailer;

    private final InMemoryPasswordResetStore store = new InMemoryPasswordResetStore();
    private PasswordResetService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new PasswordResetService(store, userRepository, passwordEncoder, mailer, TTL, 5, 5, WEB_URL);
        user = User.builder().id(UUID.randomUUID()).name("Maria").email(EMAIL).passwordHash("hash-antigo")
                .active(true).build();
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.encode(anyString())).thenAnswer(invocation -> "hash:" + invocation.getArgument(0));
    }

    // Pede a redefinição e devolve [código, link] do e-mail.
    private String[] forgot() {
        service.forgot(new ForgotPasswordRequest(EMAIL));
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(mailer).send(eq(EMAIL), eq("Maria"), code.capture(), link.capture(), eq(TTL));
        return new String[] {code.getValue(), link.getValue()};
    }

    private void assertInvalidCode(String code) {
        assertThatThrownBy(() -> service.verify(new VerifyResetCodeRequest(EMAIL, code)))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("INVALID_RESET_CODE"));
    }

    private void assertInvalidToken(String token) {
        assertThatThrownBy(() -> service.reset(new ResetPasswordRequest(token, "NovaSenha456")))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("INVALID_RESET_TOKEN"));
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    // ---------- forgot ----------

    @Test
    void forgotSendsASixDigitCodeAndAWebLink() {
        String[] mail = forgot();

        assertThat(mail[0]).matches("\\d{6}");
        assertThat(mail[1]).startsWith(WEB_URL + "?token=");
        assertThat(store.hasCode(user.getId())).isTrue();
    }

    @Test
    void forgotNormalizesTheEmail() {
        service.forgot(new ForgotPasswordRequest("  Maria@Inventra.COM "));

        verify(mailer).send(eq(EMAIL), any(), anyString(), anyString(), any());
    }

    @Test
    void forgotSendsNothingForUnknownOrInactiveAccounts() {
        when(userRepository.findByEmailIgnoreCase("ninguem@inventra.com")).thenReturn(Optional.empty());
        service.forgot(new ForgotPasswordRequest("ninguem@inventra.com"));

        user.setActive(false);
        service.forgot(new ForgotPasswordRequest(EMAIL));

        verify(mailer, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    void forgotStopsSendingAfterTheHourlyLimit() {
        for (int i = 0; i < 7; i++) {
            service.forgot(new ForgotPasswordRequest(EMAIL));
        }

        verify(mailer, times(5)).send(any(), any(), any(), any(), any());
    }

    // ---------- verify + reset ----------

    @Test
    void codeIsExchangedForATokenThatResetsThePassword() {
        String code = forgot()[0];

        ResetTokenResponse response = service.verify(new VerifyResetCodeRequest(EMAIL, code));
        assertThat(response.expiresInSeconds()).isEqualTo(TTL.toSeconds());

        service.reset(new ResetPasswordRequest(response.resetToken(), "NovaSenha456"));

        assertThat(user.getPasswordHash()).isEqualTo("hash:NovaSenha456");
        verify(userRepository).save(user);
        // o código já foi consumido
        assertInvalidCode(code);
    }

    @Test
    void linkTokenResetsThePasswordWithoutTheCode() {
        String link = forgot()[1];
        String token = link.substring(link.indexOf("?token=") + "?token=".length());

        service.reset(new ResetPasswordRequest(token, "NovaSenha456"));

        assertThat(user.getPasswordHash()).isEqualTo("hash:NovaSenha456");
        // a senha mudou: o código pendente do mesmo e-mail não vale mais
        assertThat(store.hasCode(user.getId())).isFalse();
    }

    @Test
    void codeIsDeletedOnTheFifthWrongAttempt() {
        String code = forgot()[0];

        for (int i = 0; i < 5; i++) {
            assertInvalidCode(wrong(code));
        }

        assertThat(store.hasCode(user.getId())).isFalse();
        assertInvalidCode(code);
    }

    @Test
    void verifyRejectsUnknownEmailsWithTheSameError() {
        when(userRepository.findByEmailIgnoreCase("ninguem@inventra.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(new VerifyResetCodeRequest("ninguem@inventra.com", "123456")))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("INVALID_RESET_CODE"));
    }

    @Test
    void tokenWorksOnlyOnce() {
        String token = service.verify(new VerifyResetCodeRequest(EMAIL, forgot()[0])).resetToken();
        service.reset(new ResetPasswordRequest(token, "NovaSenha456"));

        assertInvalidToken(token);
    }

    @Test
    void tokenIssuedBeforeAPasswordChangeIsRejected() {
        String link = forgot()[1];
        String token = link.substring(link.indexOf("?token=") + "?token=".length());

        // a senha mudou por outro caminho (PATCH /password ou outro reset) depois do e-mail
        user.setPasswordHash("hash-trocado");

        assertInvalidToken(token);
        assertThat(user.getPasswordHash()).isEqualTo("hash-trocado");
    }

    @Test
    void tokenOfADeactivatedAccountIsRejected() {
        String token = service.verify(new VerifyResetCodeRequest(EMAIL, forgot()[0])).resetToken();
        user.setActive(false);

        assertInvalidToken(token);
    }

    @Test
    void googleAccountWithoutPasswordCanCreateOne() {
        user.setPasswordHash(null);

        String token = service.verify(new VerifyResetCodeRequest(EMAIL, forgot()[0])).resetToken();
        service.reset(new ResetPasswordRequest(token, "NovaSenha456"));

        assertThat(user.getPasswordHash()).isEqualTo("hash:NovaSenha456");
    }

    @Test
    void unknownTokenIsRejected() {
        assertInvalidToken("token-que-nao-existe");
    }
}
