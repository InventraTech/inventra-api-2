package com.inventra.api.core.service.uiclick;

import java.util.List;
import java.util.Map;

public interface UiClickCounterUseCase {

    // Soma os cliques do lote (elemento -> quantidade). Elemento que não existe no banco é ignorado
    // e volta em ignored, para um app com versão diferente da API não perder o lote inteiro.
    UiClickBatchResult record(Map<String, Long> clicks);

    // Ranking de cliques por elemento; platform nula traz web e mobile.
    List<UiClickRanking> ranking(UiClickPlatform platform);

    record UiClickBatchResult(int recordedElements, long recordedClicks, List<String> ignored) {
    }
}
