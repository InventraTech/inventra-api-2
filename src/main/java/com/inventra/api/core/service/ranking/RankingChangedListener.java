package com.inventra.api.core.service.ranking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;


@Component
public class RankingChangedListener {
    private static final Logger log = LoggerFactory.getLogger(RankingChangedListener.class);
    private final RankingProjection projection;
    private final TaskExecutor executor;

    public RankingChangedListener(RankingProjection projection,
            @Qualifier("rankingExecutor") TaskExecutor executor) {
        this.projection = projection;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChange(StockRankingChanged event) {
        try {
            executor.execute(() -> refresh(event));
        } catch (TaskRejectedException ex) {
            log.warn("Fila local de projeção cheia kitchenId={}; recuperação via TTL", event.kitchenId(), ex);
        }
    }

    private void refresh(StockRankingChanged event) {
        try {
            projection.rebuild(event.kitchenId(), 1);
        } catch (RuntimeException ex) {
            // O estoque já foi confirmado. TTL + reconstrução na consulta recuperam o cache.
            log.error("Falha ao atualizar ranking kitchenId={}; reconstrução após expiração",
                    event.kitchenId(), ex);
        }
    }
}
