package com.inventra.api.infrastructure.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.inventra.api.infrastructure.config.RedisConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.RedisTemplate;

class RedisConnectionTest {

    private ApplicationContextRunner contextRunner() {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("REDIS_INTEGRATION_TESTS")),
                "Redis real exige REDIS_INTEGRATION_TESTS=true e ambiente dedicado de testes.");
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("REDIS_HOST") != null && System.getenv("REDIS_PORT") != null);
        return
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    DataRedisAutoConfiguration.class
                            )
                    )
                    .withUserConfiguration(RedisConfig.class)
                    .withPropertyValues(
                            "spring.data.redis.host=" + getEnv("REDIS_HOST"),
                            "spring.data.redis.port=" + getEnv("REDIS_PORT"),
                            "spring.data.redis.username=" + optionalEnv("REDIS_USERNAME"),
                            "spring.data.redis.password=" + optionalEnv("REDIS_PASSWORD"),
                            "spring.data.redis.database=" + System.getenv().getOrDefault("REDIS_DATABASE", "0"),
                            "spring.data.redis.ssl.enabled=" + System.getenv().getOrDefault("REDIS_SSL", "true"),
                            "spring.data.redis.connect-timeout=10s",
                            "spring.data.redis.timeout=10s"
                    );
    }

    @Test
    void deveSalvarELerValorNoRedis() {
        contextRunner().run(context -> {
            RedisTemplate<String, Object> redisTemplate =
                    context.getBean("redisTemplate", RedisTemplate.class);

            String key = "inventra:test:connection:" + java.util.UUID.randomUUID();
            String value = "redis-funcionando";

            redisTemplate.opsForValue().set(key, value);

            Object result = redisTemplate.opsForValue().get(key);

            assertEquals(value, result);

            redisTemplate.delete(key);
        });
    }

    private static String getEnv(String name) {
        String value = System.getenv(name);

        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Variavel de ambiente obrigatoria nao definida: " + name
            );
        }

        return value;
    }

    private static String optionalEnv(String name) {
        return System.getenv().getOrDefault(name, "");
    }
}
