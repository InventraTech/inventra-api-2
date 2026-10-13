package com.inventra.api.infrastructure.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.inventra.api.core.domain.user.User;
import com.inventra.api.core.service.user.UserUseCase;
import com.inventra.api.core.service.user.model.request.ChangePasswordRequest;
import com.inventra.api.core.service.user.model.request.CreateUserRequest;
import com.inventra.api.core.service.user.model.request.UpdateUserRequest;
import com.inventra.api.core.service.user.model.response.UserResponse;
import com.inventra.api.infrastructure.security.Roles;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@Tag(name = "Usuários", description = "Gestão de usuários")
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserUseCase useCase;

    @Operation(summary = "Cria um usuário")
    @PreAuthorize(Roles.SUPERVISOR)
    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        User created = useCase.create(request);
        UserResponse response = UserResponse.fromEntity(created);
        return ResponseEntity.created(URI.create("/api/users/" + response.id())).body(response);
    }

    @Operation(summary = "Devolve o usuário logado")
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me() {
        return ResponseEntity.ok(UserResponse.fromEntity(useCase.findCurrent()));
    }

    @Operation(summary = "Busca um usuário pelo id")
    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(UserResponse.fromEntity(useCase.findById(id)));
    }

    @Operation(summary = "Lista todos os usuários")
    @PreAuthorize(Roles.SUPERVISOR)
    @GetMapping
    public ResponseEntity<List<UserResponse>> listAll() {
        List<UserResponse> responses = useCase.listAll().stream()
                .map(UserResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @Operation(summary = "Atualiza um usuário")
    @PutMapping("/{id}")
    public ResponseEntity<UserResponse> update(@PathVariable UUID id,
                                                @Valid @RequestBody UpdateUserRequest request) {
        User updated = useCase.update(id, request);
        return ResponseEntity.ok(UserResponse.fromEntity(updated));
    }

    @Operation(summary = "Altera a senha de um usuário")
    @PatchMapping("/{id}/password")
    public ResponseEntity<Void> changePassword(@PathVariable UUID id,
                                                @Valid @RequestBody ChangePasswordRequest request) {
        useCase.changePassword(id, request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Ativa um usuário")
    @PreAuthorize(Roles.SUPERVISOR)
    @PatchMapping("/{id}/activate")
    public ResponseEntity<Void> activate(@PathVariable UUID id) {
        useCase.activate(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Desativa um usuário")
    @PreAuthorize(Roles.SUPERVISOR)
    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        useCase.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
