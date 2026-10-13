package com.inventra.api.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Habilita os jobs @Scheduled (ex.: BatchExpirationJob).
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
