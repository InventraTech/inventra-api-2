package com.inventra.api.core.service.productqueue.model;

import java.time.Instant;
import java.util.UUID;

public record ProductRegistrationResponse(UUID eventId, ProductRegistrationJob.Status status,
        Integer productId, String errorCode, Instant submittedAt, Instant startedAt, Instant finishedAt) {
    public static ProductRegistrationResponse from(ProductRegistrationJob job) {
        return new ProductRegistrationResponse(job.eventId(), job.status(), job.productId(),
                job.errorCode(), job.submittedAt(), job.startedAt(), job.finishedAt());
    }
}
