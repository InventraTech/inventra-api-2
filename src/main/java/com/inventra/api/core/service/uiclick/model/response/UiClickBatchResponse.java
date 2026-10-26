package com.inventra.api.core.service.uiclick.model.response;

import java.util.List;

import com.inventra.api.core.service.uiclick.UiClickCounterUseCase.UiClickBatchResult;

public record UiClickBatchResponse(
        // elementos que receberam cliques
        int recordedElements,
        long recordedClicks,
        // elementos que não existem no banco (nome errado ou botão novo ainda sem migration)
        List<String> ignored
) {
    public static UiClickBatchResponse from(UiClickBatchResult result) {
        return new UiClickBatchResponse(result.recordedElements(), result.recordedClicks(), result.ignored());
    }
}
