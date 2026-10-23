package com.inventra.api.infrastructure.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// Habilita @Async. A geocodificação usa um executor próprio com uma thread só, para as chamadas ao
// serviço externo saírem uma de cada vez. O envio de e-mail tem o seu, para a requisição não esperar o
// SendGrid (e o tempo de resposta não revelar se o e-mail tem conta).
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String GEOCODING_EXECUTOR = "geocodingExecutor";
    public static final String EMAIL_EXECUTOR = "emailExecutor";

    @Bean(EMAIL_EXECUTOR)
    public Executor emailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("email-");
        executor.initialize();
        return executor;
    }

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
