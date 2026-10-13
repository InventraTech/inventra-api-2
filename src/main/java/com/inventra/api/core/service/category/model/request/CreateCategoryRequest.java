package com.inventra.api.core.service.category.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCategoryRequest(
        @Schema(description = "Nome da categoria", example = "Grãos", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 80)
        String name,

        @Schema(description = "Descrição da categoria", example = "Grãos e cereais")
        @Size(max = 255)
        String description
) {
}
