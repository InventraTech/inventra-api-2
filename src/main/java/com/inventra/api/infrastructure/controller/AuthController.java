package com.inventra.api.infrastructure.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.inventra.api.core.service.auth.AuthUseCase;
import com.inventra.api.core.service.auth.model.request.GoogleLoginRequest;
import com.inventra.api.core.service.auth.model.request.LoginRequest;
import com.inventra.api.core.service.auth.model.request.RefreshTokenRequest;
import com.inventra.api.core.service.auth.model.request.RegisterRequest;
import com.inventra.api.core.service.auth.model.response.LoginResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@Tag(name = "Autenticação", description = "Login, cadastro e renovação de tokens JWT (endpoints públicos)")
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthUseCase useCase;

    @Operation(summary = "Autentica com e-mail e senha e devolve access token e refresh token")
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(useCase.login(request));
    }

    @Operation(summary = "Entra (ou cadastra) com a conta Google, a partir do ID token do Firebase",
            description = "Usuário novo sem accessType recebe 409 com code REGISTRATION_REQUIRED (e name/email do "
                    + "token): o app pergunta o cargo e chama de novo com accessType.")
    @PostMapping("/google")
    public ResponseEntity<LoginResponse> google(@Valid @RequestBody GoogleLoginRequest request) {
        return ResponseEntity.ok(useCase.loginWithGoogle(request));
    }

    @Operation(summary = "Troca um refresh token válido por um novo par de tokens")
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(useCase.refresh(request));
    }

    @Operation(summary = "Invalida o refresh token informado")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        useCase.logout(request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Cadastra um novo usuário e já devolve os tokens")
    @PostMapping("/register")
    public ResponseEntity<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(useCase.register(request));
    }
}
