package com.inventra.api.core.service.uiclick.model.request;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

// Lote de cliques que o app acumulou (inclusive os de antes do login) e envia de uma vez.
public record RecordUiClicksRequest(
        @Schema(description = "Elemento clicado (nome da coluna em tb_ui_click_counter) -> quantidade de cliques",
                example = "{\"bt_wb_login_sign_in\": 2, \"nav_mb_global_stock\": 5}")
        @NotEmpty
        @Size(max = 500)
        Map<@NotBlank @Size(max = 63) String, @NotNull @Positive @Max(10_000) Long> clicks
) {
}
