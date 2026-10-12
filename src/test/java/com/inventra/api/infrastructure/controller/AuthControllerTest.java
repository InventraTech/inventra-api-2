package com.inventra.api.infrastructure.controller;

import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventra.api.core.service.auth.AuthUseCase;
import com.inventra.api.core.service.auth.model.request.LoginRequest;
import com.inventra.api.core.service.auth.model.response.LoginResponse;
import com.inventra.api.core.service.user.model.response.UserResponse;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private AuthUseCase authUseCase;

    @Test
    void loginSucceedsWithValidCredentials() throws Exception {
        UserResponse user = new UserResponse(UUID.randomUUID(), "Maria Silva", "maria.silva@inventra.com",
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
