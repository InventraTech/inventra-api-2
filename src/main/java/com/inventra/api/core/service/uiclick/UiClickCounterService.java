package com.inventra.api.core.service.uiclick;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inventra.api.infrastructure.repository.UiClickCounterRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class UiClickCounterService implements UiClickCounterUseCase {

    private final UiClickCounterRepository repository;

    // Lista branca das colunas, lida do banco no primeiro uso: coluna nova (ADD COLUMN numa migration)
    // passa a valer sozinha. As migrations rodam antes da API subir, então ler uma vez basta.
    private volatile Set<String> counterColumns;

    @Override
    @Transactional
    public UiClickBatchResult record(Map<String, Long> clicks) {
        Set<String> allowed = counterColumns();
        TreeMap<String, Long> known = new TreeMap<>();
        List<String> ignored = new ArrayList<>();
        clicks.forEach((element, count) -> {
            if (allowed.contains(element)) {
                known.put(element, count);
            } else {
                ignored.add(element);
            }
        });

        if (!ignored.isEmpty()) {
            log.warn("Cliques ignorados de elementos que não existem em tb_ui_click_counter: {}", ignored);
        }
        if (!known.isEmpty()) {
            repository.increment(known);
        }
        long total = known.values().stream().mapToLong(Long::longValue).sum();
        return new UiClickBatchResult(known.size(), total, ignored.stream().sorted().toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<UiClickRanking> ranking(UiClickPlatform platform) {
        return repository.findRanking(platform);
    }

    private Set<String> counterColumns() {
        Set<String> columns = counterColumns;
        if (columns == null) {
            columns = repository.findCounterColumns();
            counterColumns = columns;
        }
        return columns;
    }
}
