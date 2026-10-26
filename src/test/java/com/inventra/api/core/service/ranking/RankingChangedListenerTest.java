package com.inventra.api.core.service.ranking;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

class RankingChangedListenerTest {
    @Test void saturatedExecutorDoesNotTurnCommittedStockIntoHttpFailure() {
        var projection = mock(RankingProjection.class);
        TaskExecutor executor = command -> { throw new TaskRejectedException("full"); };
        var listener = new RankingChangedListener(projection, executor);
        assertThatCode(() -> listener.onChange(new StockRankingChanged(1))).doesNotThrowAnyException();
        verifyNoInteractions(projection);
    }
}
