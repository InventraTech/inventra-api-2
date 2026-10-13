package com.inventra.api.infrastructure.redis.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

class ProductRegistrationMigrationTest {
    @TempDir Path migrations;

    @Test void v010AppliesWithoutReplacingHistoricalV2() throws Exception {
        String url = "jdbc:h2:mem:migration_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        // Histórico sintético: simula a V2 externa sem tocar na Aiven.
        Path historical = migrations.resolve("historical");
        Path current = migrations.resolve("current");
        Files.createDirectories(historical);
        Files.createDirectories(current);
        Files.writeString(historical.resolve("V2__remove_user_role_column.sql"),
                "CREATE TABLE tb_product (id_product INTEGER PRIMARY KEY);");
        Flyway.configure().dataSource(url, "sa", "").locations("filesystem:" + historical)
                .load().migrate();
        var migration = new ClassPathResource("db/migration/V010__product_registration_event.sql");
        assertThat(new ClassPathResource("db/migration/V2__product_registration_event.sql").exists()).isFalse();
        Files.write(current.resolve("V010__product_registration_event.sql"), migration.getContentAsByteArray());
        var flyway = Flyway.configure().dataSource(url, "sa", "").locations("filesystem:" + current)
                .ignoreMigrationPatterns("*:missing").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var query = connection.createStatement()) {
            query.execute("INSERT INTO tb_product (id_product, registration_event_id) VALUES (1, RANDOM_UUID())");
        }
    }
}
