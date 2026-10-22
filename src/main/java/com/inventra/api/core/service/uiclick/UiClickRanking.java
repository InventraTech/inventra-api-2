package com.inventra.api.core.service.uiclick;

import java.math.BigDecimal;

// Uma linha da vw_ui_click_ranking.
public record UiClickRanking(
        String element,
        UiClickPlatform platform,
        String typeCode,
        // vem do catálogo de dados; nula se a coluna ainda não tiver metadado
        String description,
        long clicks,
        // participação do elemento nos cliques da plataforma; nula enquanto a plataforma não tiver cliques
        BigDecimal sharePct,
        long rankInPlatform
) {
}
