package com.inventra.api.infrastructure.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// Habilita @Async. A geocodificação usa um executor próprio com uma thread só, para as chamadas ao
// serviço externo saírem uma de cada vez.
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String GEOCODING_EXECUTOR = "geocodingExecutor";

    @Bean(GEOCODING_EXECUTOR)
    public Executor geocodingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("geocoding-");
        executor.initialize();
        return executor;
    }
}
