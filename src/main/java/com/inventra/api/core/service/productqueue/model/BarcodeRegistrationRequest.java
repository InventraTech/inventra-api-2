package com.inventra.api.core.service.productqueue.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Dados revisados pelo usuário antes de solicitar o cadastro assíncrono. */
public record BarcodeRegistrationRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 80) String brand,
        @Positive Integer categoryId,
        @NotNull @Positive Integer unitId,
        @NotBlank @Pattern(regexp = "[0-9]{8,14}") String barcode
) {
}
