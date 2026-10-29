package com.inventra.api.core.service.productqueue.model;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Dados revisados pelo usuário antes de solicitar o cadastro assíncrono. O fornecedor é obrigatório, como no
 * cadastro direto: o produto nasce já vinculado a ele.
 */
public record BarcodeRegistrationRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 80) String brand,
        @Positive Integer categoryId,
        @NotNull @Positive Integer unitId,
        @NotBlank @Pattern(regexp = "[0-9]{8,14}") String barcode,
        @NotNull @Positive Integer supplierId,
        @Size(max = 50) String supplierCode,
        @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal referencePrice,
        @PositiveOrZero Integer leadTimeDays
) {
}
