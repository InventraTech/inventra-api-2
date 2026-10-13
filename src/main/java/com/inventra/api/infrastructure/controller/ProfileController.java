package com.inventra.api.infrastructure.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.inventra.api.core.domain.profile.Profile;
import com.inventra.api.core.service.profile.ProfileUseCase;
import com.inventra.api.core.service.profile.model.request.CreateProfileRequest;
import com.inventra.api.core.service.profile.model.request.UpdateProfileRequest;
import com.inventra.api.core.service.profile.model.response.ProfileResponse;
import com.inventra.api.infrastructure.security.Roles;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@Tag(name = "Perfis", description = "Perfis de acesso")
@RequestMapping("/api/profiles")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileUseCase useCase;

    @Operation(summary = "Cria um perfil")
    @PreAuthorize(Roles.SUPERVISOR)
    @PostMapping
    public ResponseEntity<ProfileResponse> create(@Valid @RequestBody CreateProfileRequest request) {
        Profile created = useCase.create(request);
        ProfileResponse response = ProfileResponse.fromEntity(created);
        return ResponseEntity.created(URI.create("/api/profiles/" + response.id())).body(response);
    }

    @Operation(summary = "Busca um perfil pelo id")
    @GetMapping("/{id}")
    public ResponseEntity<ProfileResponse> findById(@PathVariable Integer id) {
        return ResponseEntity.ok(ProfileResponse.fromEntity(useCase.findById(id)));
    }

    @Operation(summary = "Lista todos os perfis")
    @GetMapping
    public ResponseEntity<List<ProfileResponse>> listAll() {
        List<ProfileResponse> responses = useCase.listAll().stream()
                .map(ProfileResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @Operation(summary = "Atualiza um perfil")
    @PreAuthorize(Roles.SUPERVISOR)
    @PutMapping("/{id}")
    public ResponseEntity<ProfileResponse> update(@PathVariable Integer id,
                                                   @Valid @RequestBody UpdateProfileRequest request) {
        Profile updated = useCase.update(id, request);
        return ResponseEntity.ok(ProfileResponse.fromEntity(updated));
    }

    @Operation(summary = "Exclui um perfil")
    @PreAuthorize(Roles.SUPERVISOR)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Integer id) {
        useCase.delete(id);
        return ResponseEntity.noContent().build();
    }
}
