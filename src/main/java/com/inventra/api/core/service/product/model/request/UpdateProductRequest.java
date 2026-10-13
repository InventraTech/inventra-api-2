package com.inventra.api.core.service.product.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateProductRequest(
        @Schema(description = "Nome do produto", example = "Arroz branco 5kg")
        @Size(max = 150)
        @Pattern(regexp = ".*\\S.*", message = "não pode ser vazio")
        String name,

        @Schema(description = "Marca", example = "Tio João")
        @Size(max = 80)
        String brand,

        @Schema(description = "Id da categoria", example = "1")
        Integer categoryId,

        @Schema(description = "Id da unidade de medida", example = "1")
        Integer unitId,

        @Schema(description = "Código de barras", example = "7891234567895")
        @Size(max = 50)
        String barcode
) {
}
