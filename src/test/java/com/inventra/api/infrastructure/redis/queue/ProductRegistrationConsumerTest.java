package com.inventra.api.infrastructure.redis.queue;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import com.inventra.api.core.service.productqueue.ProductRegistrationProcessor;
import com.inventra.api.core.service.productqueue.model.BarcodeRegistrationRequest;
import com.inventra.api.core.service.productqueue.model.ProductRegistrationJob;
import com.inventra.api.infrastructure.exception.QueueServiceException;

class ProductRegistrationConsumerTest {
    @Test void databaseOutageLeavesJobPendingAndNextAttemptSucceeds() {
        var queue = mock(RedisProductRegistrationQueue.class);
        var processor = mock(ProductRegistrationProcessor.class);
        var job = ProductRegistrationJob.queued(UUID.randomUUID(),
                new BarcodeRegistrationRequest("Arroz", null, null, 1, "7891234567890", null));
        when(queue.find(job.eventId())).thenReturn(Optional.of(job));
        when(queue.update(any(), eq("owner"))).thenReturn(true);
        when(processor.process(any())).thenThrow(new DataAccessResourceFailureException("offline")).thenReturn(42);
        var consumer = new ProductRegistrationConsumer(queue, processor, false);
        assertThrows(QueueServiceException.class, () -> consumer.process(job.eventId(), "owner"));
        verify(queue, never()).update(argThat(j -> j.status() == ProductRegistrationJob.Status.FAILED), any());
        consumer.process(job.eventId(), "owner");
        verify(queue).update(argThat(j -> j.status() == ProductRegistrationJob.Status.COMPLETED && j.productId() == 42), eq("owner"));
    }
}
