package com.inventra.api.infrastructure.redis.queue;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.core.JacksonException;
import org.springframework.stereotype.Component;

import com.inventra.api.core.service.productqueue.ProductRegistrationProcessor;
import com.inventra.api.core.service.productqueue.model.ProductRegistrationJob;
import com.inventra.api.infrastructure.exception.BusinessRuleException;
import com.inventra.api.infrastructure.exception.QueueServiceException;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;

@Component
public class ProductRegistrationConsumer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ProductRegistrationConsumer.class);
    private static final int MAX_FAILURES = 5;
    private final RedisProductRegistrationQueue queue;
    private final ProductRegistrationProcessor processor;
    private final boolean enabled;
    private volatile boolean running;
    private volatile String leaseToken;
    private ScheduledExecutorService executor;

    public ProductRegistrationConsumer(RedisProductRegistrationQueue queue, ProductRegistrationProcessor processor,
            @Value("${app.redis.product-queue.consumer-enabled:true}") boolean enabled) {
        this.queue = queue;
        this.processor = processor;
        this.enabled = enabled;
    }

    @Override
    public synchronized void start() {
        if (running || !enabled) return;
        running = true;
        executor = Executors.newScheduledThreadPool(2, Thread.ofPlatform().daemon(true)
                .name("product-registration-", 0).factory());
        executor.scheduleWithFixedDelay(this::renewLease, 5, 5, TimeUnit.SECONDS);
        executor.execute(this::consume);
    }

    private void consume() {
        while (running) {
            String token = UUID.randomUUID().toString();
            try {
                if (!queue.acquire(token)) {
                    pause();
                    continue;
                }
                leaseToken = token;
                queue.recover(token);
                while (running && token.equals(leaseToken)) {
                    UUID eventId = queue.take(token);
                    if (eventId != null && token.equals(leaseToken)) process(eventId, token);
                }
            } catch (RuntimeException ex) {
                // Pendências continuam no Redis; o próximo ciclo reconstrói a lista.
                log.warn("Fila de cadastro temporariamente indisponível timestamp={} errorType={}",
                        Instant.now(), ex.getClass().getSimpleName(), ex);
                pause();
            } finally {
                leaseToken = null;
                try { queue.release(token); }
                catch (RuntimeException ex) { log.warn("Concessão da fila aguardará expiração.", ex); }
            }
        }
    }

    void process(UUID eventId, String token) {
        ProductRegistrationJob job;
        try {
            job = queue.find(eventId).orElse(null);
        } catch (JacksonException ex) {
            log.error("Payload inválido eventId={}", eventId, ex);
            quarantine(eventId, token, "INVALID_PAYLOAD");
            return;
        }
        if (job == null) {
            quarantine(eventId, token, "PAYLOAD_MISSING");
            return;
        }
        if (!eventId.equals(job.eventId()) || job.userId() == null || job.request() == null
                || job.status() == null || job.submittedAt() == null) {
            quarantine(eventId, token, "INVALID_PAYLOAD");
            return;
        }
        if (job.status() == ProductRegistrationJob.Status.COMPLETED
                || job.status() == ProductRegistrationJob.Status.FAILED) {
            if (!queue.update(job, token)) throw new QueueServiceException("Confirmação não realizada.");
            return;
        }
        if (job.attempts() >= MAX_FAILURES) {
            if (!queue.update(job.failed("RETRY_LIMIT_EXCEEDED"), token))
                throw new QueueServiceException("Confirmação não realizada.");
            return;
        }
        if (job.nextAttemptAt() != null && Instant.now().isBefore(job.nextAttemptAt())) {
            throw new QueueServiceException("Cadastro aguardando próxima tentativa.");
        }
        ProductRegistrationJob processing = job.processing();
        if (!queue.update(processing, token)) return;
        log.info("Cadastro iniciado eventId={} timestamp={}", eventId, processing.startedAt());
        ProductRegistrationJob result;
        try {
            result = processing.completed(processor.process(processing));
        } catch (RuntimeException ex) {
            if (ex instanceof TransientDataAccessException
                    || ex instanceof DataAccessResourceFailureException
                    || ex instanceof CannotCreateTransactionException) {
                var retry = processing.retry();
                log.warn("Falha transitória eventId={} tentativa={}", eventId, retry.attempts(), ex);
                if (retry.attempts() < MAX_FAILURES) {
                    if (!queue.update(retry, token)) throw new QueueServiceException("Lease perdido.", ex);
                    throw new QueueServiceException("Banco temporariamente indisponível; cadastro permanece pendente.", ex);
                }
                result = retry.failed("RETRY_LIMIT_EXCEEDED");
            } else {
                if (!(ex instanceof ResourceNotFoundException) && !(ex instanceof BusinessRuleException)) {
                    log.error("Falha no cadastro eventId={}", eventId, ex);
                }
                String code = ex instanceof ResourceNotFoundException ? "REFERENCE_NOT_FOUND"
                        : ex instanceof BusinessRuleException ? "BUSINESS_RULE"
                        : ex instanceof DataAccessException ? "DATABASE_ERROR" : "PROCESSING_ERROR";
                result = processing.failed(code);
            }
        }
        // Falha de confirmação no Redis não transforma um sucesso no PostgreSQL em DLQ.
        if (!queue.update(result, token)) throw new QueueServiceException("Confirmação de cadastro não realizada.");
        log.info("Cadastro finalizado eventId={} status={} errorCode={} timestamp={}",
                eventId, result.status(), result.errorCode(), result.finishedAt());
    }

    private void quarantine(UUID eventId, String token, String code) {
        if (!queue.quarantine(eventId, token, code)) throw new QueueServiceException("Quarentena não confirmada.");
        log.error("Job irrecuperável enviado à DLQ eventId={} errorCode={}", eventId, code);
    }

    private void renewLease() {
        String token = leaseToken;
        if (token == null) return;
        try {
            if (!queue.renew(token)) leaseToken = null;
        } catch (RuntimeException ex) {
            leaseToken = null;
            log.warn("Falha ao renovar concessão da fila.", ex);
        }
    }

    private void pause() {
        try { TimeUnit.SECONDS.sleep(3); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); running = false; }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(15, TimeUnit.SECONDS)) executor.shutdownNow();
            } catch (InterruptedException ex) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return enabled; }
}
