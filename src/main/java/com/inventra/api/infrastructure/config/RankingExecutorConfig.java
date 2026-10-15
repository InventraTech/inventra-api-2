package com.inventra.api.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class RankingExecutorConfig {
    @Bean
    public ThreadPoolTaskExecutor rankingExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(128);
        executor.setThreadNamePrefix("ranking-projection-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        // Rejeição é tratada pelo listener; nunca executa reconstrução na thread do commit.
        return executor;
    }
}
