package com.inventra.api.core.service.product.model.request;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

// Sem photoUrl: a foto só entra pelo upload (PUT /api/products/{id}/photo), que valida o arquivo e gera
// a URL do Cloudinary — aceitar uma URL livre aqui abria espaço para links maliciosos (ex.: javascript:).
// Produto não nasce sem fornecedor: o vínculo (POST /api/products/{id}/suppliers) é gravado junto, na mesma
// transação. Os campos do fornecedor são os mesmos do LinkSupplierRequest.
public record CreateProductRequest(
        @Schema(description = "Nome do produto", example = "Arroz branco 5kg", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 150)
        String name,

        @Schema(description = "Marca", example = "Tio João")
        @Size(max = 80)
        String brand,

        @Schema(description = "Id da categoria", example = "1")
        Integer categoryId,

        @Schema(description = "Id da unidade de medida", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Integer unitId,

        @Schema(description = "Código de barras", example = "7891234567895")
        @Size(max = 50)
        String barcode,

        @Schema(description = "Id do fornecedor (ativo) do produto", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Integer supplierId,

        @Schema(description = "Código do produto no fornecedor", example = "ARZ-001")
        @Size(max = 50)
        String supplierCode,

        @Schema(description = "Preço de referência no fornecedor", example = "25.90")
        @PositiveOrZero
        @Digits(integer = 10, fraction = 2)
        BigDecimal referencePrice,

        @Schema(description = "Prazo de entrega do fornecedor em dias", example = "3")
        @PositiveOrZero
        Integer leadTimeDays
) {
}
