package com.inventra.api.infrastructure.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.inventra.api.infrastructure.repository.StockBatchRepository;

import lombok.RequiredArgsConstructor;

// Marca lotes vencidos como EXPIRED (e gera os alertas) via sp_expire_batches e avisa dos lotes que vão
// vencer nos próximos dias via sp_alert_expiring_batches, todo dia logo depois da meia-noite de Brasília
// e também na subida da aplicação — o Render pode ter deixado a instância parada na virada do dia.
// Desligável por app.jobs.batch-expiration.enabled (os testes com H2 desligam: as procedures só existem
// no Postgres).
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.jobs.batch-expiration.enabled", havingValue = "true", matchIfMissing = true)
public class BatchExpirationJob {

    private static final Logger log = LoggerFactory.getLogger(BatchExpirationJob.class);

    private final StockBatchRepository stockBatchRepository;

    // quantos dias antes do vencimento o alerta EXPIRING nasce
    @Value("${app.alerts.expiring-days:3}")
    private int expiringDays = 3;

    @Scheduled(cron = "0 5 0 * * *", zone = "America/Sao_Paulo")
    public void expireBatches() {
        stockBatchRepository.callExpireBatches();
        log.info("Lotes vencidos marcados como EXPIRED");
        stockBatchRepository.callAlertExpiringBatches(expiringDays);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void expireOnStartup() {
        try {
            expireBatches();
        } catch (RuntimeException ex) {
            // não derruba a subida da API por causa do job; a próxima execução agendada tenta de novo
            log.error("Falha ao marcar lotes vencidos na inicialização", ex);
        }
    }
}
