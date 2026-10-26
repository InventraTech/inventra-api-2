package com.inventra.api.infrastructure.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventra.api.core.service.auth.AuthUseCase;
import com.inventra.api.core.service.auth.model.request.LoginRequest;
import com.inventra.api.core.service.auth.model.response.LoginResponse;
import com.inventra.api.core.service.passwordreset.PasswordResetUseCase;
import com.inventra.api.core.service.passwordreset.model.request.ForgotPasswordRequest;
import com.inventra.api.core.service.passwordreset.model.request.ResetPasswordRequest;
import com.inventra.api.core.service.passwordreset.model.request.VerifyResetCodeRequest;
import com.inventra.api.core.service.passwordreset.model.response.ResetTokenResponse;
import com.inventra.api.core.service.user.model.response.UserResponse;
import com.inventra.api.infrastructure.exception.ApiProblemException;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private AuthUseCase authUseCase;

    @MockitoBean
    private PasswordResetUseCase passwordResetUseCase;

    @Test
    void forgotPasswordAlwaysAnswersAccepted() throws Exception {
        mockMvc.perform(post("/api/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ForgotPasswordRequest("maria@inventra.com"))))
                .andExpect(status().isAccepted());

        verify(passwordResetUseCase).forgot(new ForgotPasswordRequest("maria@inventra.com"));
    }

    @Test
    void forgotPasswordRejectsAnInvalidEmail() throws Exception {
        mockMvc.perform(post("/api/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ForgotPasswordRequest("nao-e-email"))))
                .andExpect(status().isBadRequest());

        verify(passwordResetUseCase, never()).forgot(any());
    }

    @Test
    void verifyResetCodeReturnsTheResetToken() throws Exception {
        when(passwordResetUseCase.verify(any())).thenReturn(new ResetTokenResponse("token-abc", 900));

        mockMvc.perform(post("/api/auth/password/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VerifyResetCodeRequest("maria@inventra.com", "482915"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resetToken").value("token-abc"))
                .andExpect(jsonPath("$.expiresInSeconds").value(900));
    }

    @Test
    void verifyResetCodeRejectsCodesThatAreNotSixDigits() throws Exception {
        mockMvc.perform(post("/api/auth/password/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VerifyResetCodeRequest("maria@inventra.com", "12ab"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void verifyResetCodeReportsAWrongCode() throws Exception {
        when(passwordResetUseCase.verify(any())).thenThrow(
                new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_RESET_CODE", "Código inválido ou expirado."));

        mockMvc.perform(post("/api/auth/password/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VerifyResetCodeRequest("maria@inventra.com", "000000"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RESET_CODE"));
    }

    @Test
    void resetPasswordAnswersNoContent() throws Exception {
        mockMvc.perform(post("/api/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResetPasswordRequest("token-abc", "NovaSenha456"))))
                .andExpect(status().isNoContent());
    }

    @Test
    void resetPasswordRejectsAShortPassword() throws Exception {
        mockMvc.perform(post("/api/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResetPasswordRequest("token-abc", "curta"))))
                .andExpect(status().isBadRequest());

        verify(passwordResetUseCase, never()).reset(any());
    }

    @Test
    void resetPasswordReportsAnInvalidToken() throws Exception {
        doThrow(new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_RESET_TOKEN", "Token inválido."))
                .when(passwordResetUseCase).reset(any());

        mockMvc.perform(post("/api/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResetPasswordRequest("token-velho", "NovaSenha456"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RESET_TOKEN"));
    }

    @Test
    void loginSucceedsWithValidCredentials() throws Exception {
        UserResponse user = new UserResponse(UUID.randomUUID(), "Maria Silva", "maria.silva@inventra.com", null,
                null, new UserResponse.ProfileSummary(1, "ADMIN"), true, true, null, LocalDateTime.now());
        when(authUseCase.login(any())).thenReturn(new LoginResponse("token123", "Bearer", 3600L, user));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("maria.silva@inventra.com", "SenhaForte123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("token123"))
                .andExpect(jsonPath("$.user.email").value("maria.silva@inventra.com"));
    }

    @Test
    void loginFailsWithWrongPassword() throws Exception {
        when(authUseCase.login(any())).thenThrow(new BadCredentialsException("Credenciais inválidas"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("maria.silva@inventra.com", "senhaErrada"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginFailsForDisabledAccount() throws Exception {
        when(authUseCase.login(any())).thenThrow(new DisabledException("Conta desativada"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("maria.silva@inventra.com", "SenhaForte123"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginRejectsBlankFields() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("", ""))))
                .andExpect(status().isBadRequest());
    }
}
