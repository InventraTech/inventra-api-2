package com.inventra.api.core.service.stockbatch.model.request;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record RegisterStockEntryRequest(
        @Schema(description = "Id do produto", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Integer productId,

        @Schema(description = "Id da cozinha", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Integer kitchenId,

        @Schema(description = "Id do fornecedor", example = "1")
        Integer supplierId,

        @Schema(description = "Número do lote", example = "L2026-001", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 50)
        String batchNumber,

        @Schema(description = "Número da nota fiscal", example = "12345")
        @Size(max = 50)
        String invoiceNumber,

        @Schema(description = "Quantidade inicial do lote", example = "20.000", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        @Positive
        @Digits(integer = 9, fraction = 3)
        BigDecimal initialQuantity,

        @Schema(description = "Data de entrada", example = "2026-10-01", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        LocalDate entryDate,

        @Schema(description = "Data de validade", example = "2026-12-31")
        LocalDate expirationDate,

        @Schema(description = "Preço unitário", example = "4.50")
        @PositiveOrZero
        @Digits(integer = 10, fraction = 2)
        BigDecimal unitPrice
) {
}
