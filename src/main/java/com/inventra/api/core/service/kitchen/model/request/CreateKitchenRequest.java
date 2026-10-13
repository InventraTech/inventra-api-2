package com.inventra.api.core.service.kitchen.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// Localização é opcional: o app normalmente manda pronta (GPS ou geocodificação no aparelho); sem
// coordenadas, a API tenta descobri-las pelo endereço em segundo plano (KitchenGeocodingService).
public record CreateKitchenRequest(
        @Schema(description = "Nome da cozinha", example = "Cozinha Central", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 120)
        String name,

        @Schema(description = "Endereço", example = "Rua das Flores, 100")
        @Size(max = 255)
        String address,

        @Schema(description = "Cidade", example = "São Paulo")
        @Size(max = 120)
        String city,

        @Schema(description = "UF, com 2 letras", example = "SP")
        @Pattern(regexp = "[A-Za-z]{2}", message = "deve ter 2 letras")
        String state,

        @Schema(description = "Latitude do estabelecimento, de -90 a 90", example = "-23.55052")
        @DecimalMin("-90.0")
        @DecimalMax("90.0")
        Double latitude,

        @Schema(description = "Longitude do estabelecimento, de -180 a 180", example = "-46.633308")
        @DecimalMin("-180.0")
        @DecimalMax("180.0")
        Double longitude
) {
}
