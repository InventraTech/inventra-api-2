package com.inventra.api.infrastructure.redis.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;

import com.inventra.api.core.service.productqueue.ProductRegistrationProcessor;
import com.inventra.api.core.service.productqueue.model.BarcodeRegistrationRequest;
import com.inventra.api.core.service.productqueue.model.ProductRegistrationJob;
import com.inventra.api.infrastructure.exception.QueueServiceException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

class ProductRegistrationConsumerTest {
    @Test void lostAcknowledgementAfterLastAllowedExecutionDoesNotTurnSuccessIntoFailure() {
        var queue = mock(RedisProductRegistrationQueue.class);
        var processor = mock(ProductRegistrationProcessor.class);
        var initial = ProductRegistrationJob.queued(UUID.randomUUID(),
                new BarcodeRegistrationRequest("Arroz", null, null, 1, "7891234567890"));
        for (int i = 0; i < 4; i++) initial = initial.retry();
        var stored = new AtomicReference<>(initial.processing());
        var loseAcknowledgement = new AtomicBoolean(true);
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
        assertThat(stored.get().status()).isEqualTo(ProductRegistrationJob.Status.COMPLETED);
        verify(queue, never()).update(argThat(j -> j.status() == ProductRegistrationJob.Status.FAILED), any());
    }

    @Test void backoffDoesNotConsumeAnotherAttemptBeforeItsDeadline() {
        var queue = mock(RedisProductRegistrationQueue.class);
        var processor = mock(ProductRegistrationProcessor.class);
        var retry = ProductRegistrationJob.queued(UUID.randomUUID(),
                new BarcodeRegistrationRequest("Arroz", null, null, 1, "7891234567890")).retry();
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
        RuntimeException decodeError = assertThrows(JacksonException.class,
                () -> JsonMapper.builder().build()
                        .readValue("{", ProductRegistrationJob.class));
        when(queue.find(corrupt)).thenThrow(decodeError);
        consumer.process(corrupt, "owner");
        verify(queue).quarantine(corrupt, "owner", "INVALID_PAYLOAD");
        verifyNoInteractions(processor);
    }

    @Test void transientFailuresRemainBoundedAcrossConsumerRestarts() {
        var queue = mock(RedisProductRegistrationQueue.class);
        var processor = mock(ProductRegistrationProcessor.class);
        var stored = new AtomicReference<>(ProductRegistrationJob.queued(UUID.randomUUID(),
                new BarcodeRegistrationRequest("Arroz", null, null, 1, "7891234567890")));
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
            assertThat(retry.nextAttemptAt()).isAfter(Instant.now());
            stored.set(new ProductRegistrationJob(retry.eventId(), retry.userId(), retry.request(), retry.status(),
                    retry.productId(), retry.errorCode(), retry.submittedAt(), retry.startedAt(), retry.finishedAt(),
                    retry.attempts(), Instant.EPOCH));
        }
        new ProductRegistrationConsumer(queue, processor, false).process(stored.get().eventId(), "owner");
        assertThat(stored.get().attempts()).isEqualTo(5);
        assertThat(stored.get().errorCode()).isEqualTo("RETRY_LIMIT_EXCEEDED");
        assertThat(stored.get().status()).isEqualTo(ProductRegistrationJob.Status.FAILED);
        verify(processor, times(5)).process(any());
    }

    @Test void infrastructureReadFailureDoesNotQuarantineAValidJob() {
        var queue = mock(RedisProductRegistrationQueue.class);
        var processor = mock(ProductRegistrationProcessor.class);
        when(queue.find(any())).thenThrow(new RedisConnectionFailureException("offline"));
        assertThrows(RedisConnectionFailureException.class,
                () -> new ProductRegistrationConsumer(queue, processor, false).process(UUID.randomUUID(), "owner"));
        verify(queue, never()).quarantine(any(), any(), any());
    }

    @Test void legacyJsonWithoutAttemptsRemainsReadable() {
        var job = JsonMapper.builder().build()
                .readValue("{\"status\":\"QUEUED\"}", ProductRegistrationJob.class);
        assertThat(job.attempts()).isZero();
    }
    @Test void databaseOutageLeavesJobPendingAndNextAttemptSucceeds() {
        var queue = mock(RedisProductRegistrationQueue.class);
        var processor = mock(ProductRegistrationProcessor.class);
        var job = ProductRegistrationJob.queued(UUID.randomUUID(),
                new BarcodeRegistrationRequest("Arroz", null, null, 1, "7891234567890"));
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
