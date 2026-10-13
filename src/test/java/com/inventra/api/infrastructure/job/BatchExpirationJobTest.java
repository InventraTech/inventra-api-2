package com.inventra.api.infrastructure.job;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import com.inventra.api.infrastructure.repository.StockBatchRepository;

@ExtendWith(MockitoExtension.class)
class BatchExpirationJobTest {

    @Mock private StockBatchRepository stockBatchRepository;

    @InjectMocks private BatchExpirationJob job;

    @Test
    void expireBatchesCallsTheExpirationProcedure() {
        job.expireBatches();

        verify(stockBatchRepository).callExpireBatches();
    }

    @Test
    void startupRunNeverBreaksApplicationBootWhenTheProcedureFails() {
        doThrow(new DataAccessResourceFailureException("banco fora do ar")).when(stockBatchRepository).callExpireBatches();

        assertDoesNotThrow(() -> job.expireOnStartup());
    }
}
