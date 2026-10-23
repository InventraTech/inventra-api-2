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
import com.inventra.api.core.service.passwordreset.PasswordResetUseCase;
import com.inventra.api.core.service.passwordreset.model.request.ForgotPasswordRequest;
import com.inventra.api.core.service.passwordreset.model.request.ResetPasswordRequest;
import com.inventra.api.core.service.passwordreset.model.request.VerifyResetCodeRequest;
import com.inventra.api.core.service.passwordreset.model.response.ResetTokenResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@Tag(name = "Autenticação", description = "Login, cadastro, renovação de tokens JWT e redefinição de senha (endpoints públicos)")
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthUseCase useCase;
    private final PasswordResetUseCase passwordResetUseCase;

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
    @ApiResponse(responseCode = "204", description = "Operação concluída, sem corpo na resposta")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        useCase.logout(request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Cadastra um novo usuário e já devolve os tokens")
    @ApiResponse(responseCode = "201", description = "Recurso criado")
    @PostMapping("/register")
    public ResponseEntity<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(useCase.register(request));
    }

    @Operation(summary = "Pede a redefinição de senha: envia por e-mail um código de 6 dígitos e um link para o web",
            description = "Responde 202 sempre, mesmo se o e-mail não tiver conta, para não revelar quem está "
                    + "cadastrado. No máximo 5 pedidos por e-mail por hora; acima disso nada é enviado.")
    @ApiResponse(responseCode = "202", description = "Pedido aceito; se o e-mail tiver conta ativa, o código é enviado")
    @PostMapping("/password/forgot")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetUseCase.forgot(request);
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Troca o código recebido por e-mail por um token de redefinição",
            description = "Código errado ou expirado responde 400 com code INVALID_RESET_CODE. Depois de 5 "
                    + "tentativas erradas o código é apagado e é preciso pedir outro.")
    @PostMapping("/password/verify")
    public ResponseEntity<ResetTokenResponse> verifyResetCode(@Valid @RequestBody VerifyResetCodeRequest request) {
        return ResponseEntity.ok(passwordResetUseCase.verify(request));
    }

    @Operation(summary = "Define a nova senha com o token de redefinição (do /verify ou do link do e-mail)",
            description = "O token vale uma vez só. Token inválido, expirado ou já usado responde 400 com code "
                    + "INVALID_RESET_TOKEN. Trocar a senha encerra todas as sessões abertas.")
    @ApiResponse(responseCode = "204", description = "Operação concluída, sem corpo na resposta")
    @PostMapping("/password/reset")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetUseCase.reset(request);
        return ResponseEntity.noContent().build();
    }
}
