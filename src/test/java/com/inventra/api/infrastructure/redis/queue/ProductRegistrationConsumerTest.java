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
    @Test void lostAcknowledgementAfterLastAllowedExecutionDoesNotTurnSuccessIntoFailure() {
        var queue = mock(RedisProductRegistrationQueue.class);
        var processor = mock(ProductRegistrationProcessor.class);
        var initial = ProductRegistrationJob.queued(UUID.randomUUID(),
                new BarcodeRegistrationRequest("Arroz", null, null, 1, "7891234567890", null));
        for (int i = 0; i < 4; i++) initial = initial.retry();
        var stored = new java.util.concurrent.atomic.AtomicReference<>(initial.processing());
        var loseAcknowledgement = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(queue.find(any())).thenAnswer(invocation -> Optional.of(stored.get()));
        when(queue.update(any(), eq("owner"))).thenAnswer(invocation -> {
            ProductRegistrationJob update = invocation.getArgument(0);
            if (update.status() == ProductRegistrationJob.Status.COMPLETED && loseAcknowledgement.getAndSet(false))
                return false;
            stored.set(update);
            return true;
        });
        when(processor.process(any())).thenReturn(42);
        var consumer = new ProductRegistrationConsumer(queue, processor, false);
        assertThrows(QueueServiceException.class, () -> consumer.process(stored.get().eventId(), "owner"));
        consumer.process(stored.get().eventId(), "owner");
        org.assertj.core.api.Assertions.assertThat(stored.get().status()).isEqualTo(ProductRegistrationJob.Status.COMPLETED);
        verify(queue, never()).update(argThat(j -> j.status() == ProductRegistrationJob.Status.FAILED), any());
    }

    @Test void backoffDoesNotConsumeAnotherAttemptBeforeItsDeadline() {
        var queue = mock(RedisProductRegistrationQueue.class);
        var processor = mock(ProductRegistrationProcessor.class);
        var retry = ProductRegistrationJob.queued(UUID.randomUUID(),
                new BarcodeRegistrationRequest("Arroz", null, null, 1, "7891234567890", null)).retry();
        when(queue.find(retry.eventId())).thenReturn(Optional.of(retry));
        assertThrows(QueueServiceException.class,
                () -> new ProductRegistrationConsumer(queue, processor, false).process(retry.eventId(), "owner"));
        verifyNoInteractions(processor);
        verify(queue, never()).update(any(), any());
    }
    @Test void missingAndMalformedPayloadsAreQuarantined() {
        var queue = mock(RedisProductRegistrationQueue.class);
        var processor = mock(ProductRegistrationProcessor.class);
        var consumer = new ProductRegistrationConsumer(queue, processor, false);
        var missing = UUID.randomUUID();
        when(queue.quarantine(any(), eq("owner"), any())).thenReturn(true);
        consumer.process(missing, "owner");
        verify(queue).quarantine(missing, "owner", "PAYLOAD_MISSING");
        var corrupt = UUID.randomUUID();
        RuntimeException decodeError = assertThrows(tools.jackson.core.JacksonException.class,
                () -> tools.jackson.databind.json.JsonMapper.builder().build()
                        .readValue("{", ProductRegistrationJob.class));
        when(queue.find(corrupt)).thenThrow(decodeError);
        consumer.process(corrupt, "owner");
        verify(queue).quarantine(corrupt, "owner", "INVALID_PAYLOAD");
        verifyNoInteractions(processor);
    }

    @Test void transientFailuresRemainBoundedAcrossConsumerRestarts() {
        var queue = mock(RedisProductRegistrationQueue.class);
        var processor = mock(ProductRegistrationProcessor.class);
        var stored = new java.util.concurrent.atomic.AtomicReference<>(ProductRegistrationJob.queued(UUID.randomUUID(),
                new BarcodeRegistrationRequest("Arroz", null, null, 1, "7891234567890", null)));
        when(queue.find(any())).thenAnswer(invocation -> Optional.of(stored.get()));
        when(queue.update(any(), eq("owner"))).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return true;
        });
        when(processor.process(any())).thenThrow(new DataAccessResourceFailureException("offline"));
        for (int i = 0; i < 4; i++) {
            var restarted = new ProductRegistrationConsumer(queue, processor, false);
            assertThrows(QueueServiceException.class, () -> restarted.process(stored.get().eventId(), "owner"));
            var retry = stored.get();
            org.assertj.core.api.Assertions.assertThat(retry.nextAttemptAt()).isAfter(java.time.Instant.now());
            stored.set(new ProductRegistrationJob(retry.eventId(), retry.userId(), retry.request(), retry.status(),
                    retry.productId(), retry.errorCode(), retry.submittedAt(), retry.startedAt(), retry.finishedAt(),
                    retry.attempts(), java.time.Instant.EPOCH));
        }
        new ProductRegistrationConsumer(queue, processor, false).process(stored.get().eventId(), "owner");
        org.assertj.core.api.Assertions.assertThat(stored.get().attempts()).isEqualTo(5);
        org.assertj.core.api.Assertions.assertThat(stored.get().errorCode()).isEqualTo("RETRY_LIMIT_EXCEEDED");
        org.assertj.core.api.Assertions.assertThat(stored.get().status()).isEqualTo(ProductRegistrationJob.Status.FAILED);
        verify(processor, times(5)).process(any());
    }

    @Test void infrastructureReadFailureDoesNotQuarantineAValidJob() {
        var queue = mock(RedisProductRegistrationQueue.class);
        var processor = mock(ProductRegistrationProcessor.class);
        when(queue.find(any())).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("offline"));
        assertThrows(org.springframework.data.redis.RedisConnectionFailureException.class,
                () -> new ProductRegistrationConsumer(queue, processor, false).process(UUID.randomUUID(), "owner"));
        verify(queue, never()).quarantine(any(), any(), any());
    }

    @Test void legacyJsonWithoutAttemptsRemainsReadable() {
        var job = tools.jackson.databind.json.JsonMapper.builder().build()
                .readValue("{\"status\":\"QUEUED\"}", ProductRegistrationJob.class);
        org.assertj.core.api.Assertions.assertThat(job.attempts()).isZero();
    }
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
