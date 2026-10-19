package com.inventra.api.infrastructure.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.inventra.api.core.service.productqueue.ProductRegistrationService;
import com.inventra.api.core.service.productqueue.model.BarcodeRegistrationRequest;
import com.inventra.api.core.service.productqueue.model.ProductRegistrationResponse;
import com.inventra.api.infrastructure.security.Roles;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@Tag(name = "Produtos", description = "Catálogo de produtos, fornecedores e parâmetros por cozinha")
@RequestMapping("/api/products/barcode-registrations")
@RequiredArgsConstructor
public class ProductRegistrationController {
    private final ProductRegistrationService service;

    @Operation(summary = "Enfileira o cadastro de um produto lido pelo código de barras",
            description = "O cadastro é processado em segundo plano pela fila do Redis. Acompanhe o resultado "
                    + "pelo eventId devolvido (ou pelo header Location).")
    @ApiResponse(responseCode = "202", description = "Cadastro aceito e enfileirado")
    @PreAuthorize(Roles.SUPERVISOR)
    @PostMapping
    public ResponseEntity<ProductRegistrationResponse> enqueue(@Valid @RequestBody BarcodeRegistrationRequest request) {
        var response = service.enqueue(request);
        return ResponseEntity.accepted()
                .location(URI.create("/api/products/barcode-registrations/" + response.eventId())).body(response);
    }

    @Operation(summary = "Consulta o status de um cadastro enfileirado pelo eventId")
    @GetMapping("/{eventId}")
    public ResponseEntity<ProductRegistrationResponse> find(@PathVariable UUID eventId) {
        return ResponseEntity.ok(service.find(eventId));
    }
}
