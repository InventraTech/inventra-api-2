package com.inventra.api.infrastructure.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.inventra.api.core.service.profile.ProfileUseCase;
import com.inventra.api.core.service.profile.model.response.ProfileResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@Tag(name = "Perfis", description = "Perfis de acesso")
@RequestMapping("/api/profiles")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileUseCase useCase;

    @Operation(summary = "Lista todos os perfis")
    @GetMapping
    public ResponseEntity<List<ProfileResponse>> listAll() {
        List<ProfileResponse> responses = useCase.listAll().stream()
                .map(ProfileResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(responses);
    }
}
