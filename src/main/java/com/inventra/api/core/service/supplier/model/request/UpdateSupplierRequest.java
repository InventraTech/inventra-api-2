package com.inventra.api.core.service.supplier.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record UpdateSupplierRequest(
        @Schema(description = "Razão social", example = "Distribuidora Alimentos Ltda")
        @Size(max = 150)
        String legalName,

        @Schema(description = "E-mail", example = "maria@inventra.com")
        @Email
        @Size(max = 150)
        String email,

        @Schema(description = "WhatsApp", example = "11999999999")
        @Size(max = 20)
        String whatsapp,

        @Schema(description = "Avaliação de 1 a 5", example = "4")
        @Min(1)
        @Max(5)
        Integer rating
) {
}
