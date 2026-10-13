package com.inventra.api.core.service.productqueue.model;

import java.time.Instant;
import java.util.UUID;

public record ProductRegistrationJob(
        UUID eventId,
        UUID userId,
        BarcodeRegistrationRequest request,
        Status status,
        Integer productId,
        String errorCode,
        Instant submittedAt,
        Instant startedAt,
        Instant finishedAt,
        Integer attempts,
        Instant nextAttemptAt
) {
    public ProductRegistrationJob {
        attempts = attempts == null ? 0 : attempts;
        if (attempts < 0) throw new IllegalArgumentException("Tentativas inválidas.");
    }
    public enum Status { QUEUED, PROCESSING, COMPLETED, FAILED }

    public static ProductRegistrationJob queued(UUID userId, BarcodeRegistrationRequest request) {
        return new ProductRegistrationJob(UUID.randomUUID(), userId, request, Status.QUEUED,
                null, null, Instant.now(), null, null, 0, null);
    }

    public ProductRegistrationJob processing() {
        return new ProductRegistrationJob(eventId, userId, request, Status.PROCESSING,
                null, null, submittedAt, Instant.now(), null, attempts, null);
    }

    public ProductRegistrationJob completed(Integer id) {
        return new ProductRegistrationJob(eventId, userId, request, Status.COMPLETED,
                id, null, submittedAt, startedAt, Instant.now(), attempts, null);
    }

    public ProductRegistrationJob failed(String code) {
        return new ProductRegistrationJob(eventId, userId, request, Status.FAILED,
                null, code, submittedAt, startedAt, Instant.now(), attempts, null);
    }

    public ProductRegistrationJob retry() {
        return new ProductRegistrationJob(eventId, userId, request, Status.QUEUED,
                null, "DATABASE_RETRY", submittedAt, startedAt, null, attempts + 1,
                Instant.now().plusSeconds(15L << Math.min(attempts, 4)));
    }
}
