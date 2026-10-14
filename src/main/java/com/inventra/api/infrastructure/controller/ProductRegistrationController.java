package com.inventra.api.infrastructure.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.inventra.api.core.service.productqueue.ProductRegistrationService;
import com.inventra.api.core.service.productqueue.model.BarcodeRegistrationRequest;
import com.inventra.api.core.service.productqueue.model.ProductRegistrationResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/products/barcode-registrations")
@RequiredArgsConstructor
public class ProductRegistrationController {
    private final ProductRegistrationService service;

    @PostMapping
    public ResponseEntity<ProductRegistrationResponse> enqueue(@Valid @RequestBody BarcodeRegistrationRequest request) {
        var response = service.enqueue(request);
        return ResponseEntity.accepted()
                .location(URI.create("/api/products/barcode-registrations/" + response.eventId())).body(response);
    }

    @GetMapping("/{eventId}")
    public ResponseEntity<ProductRegistrationResponse> find(@PathVariable UUID eventId) {
        return ResponseEntity.ok(service.find(eventId));
    }
}
