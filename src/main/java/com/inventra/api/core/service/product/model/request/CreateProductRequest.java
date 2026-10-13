package com.inventra.api.core.service.product.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Sem photoUrl: a foto só entra pelo upload (PUT /api/products/{id}/photo), que valida o arquivo e gera
// a URL do Cloudinary — aceitar uma URL livre aqui abria espaço para links maliciosos (ex.: javascript:).
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
        String barcode
) {
}
