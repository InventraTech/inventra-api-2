package com.inventra.api.core.service.category.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateCategoryRequest(
        @Schema(description = "Nome da categoria", example = "Grãos")
        @Size(max = 80)
        @Pattern(regexp = ".*\\S.*", message = "não pode ser vazio")
        String name,

        @Schema(description = "Descrição da categoria", example = "Grãos e cereais")
        @Size(max = 255)
        String description
) {
}
