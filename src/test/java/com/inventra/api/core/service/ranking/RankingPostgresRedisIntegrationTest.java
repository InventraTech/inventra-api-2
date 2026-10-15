package com.inventra.api.core.service.ranking;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.product.Product;
import com.inventra.api.core.domain.profile.Profile;
import com.inventra.api.core.domain.stock.enums.StockMovementReason;
import com.inventra.api.core.domain.unit.Unit;
import com.inventra.api.core.domain.user.User;
import com.inventra.api.core.service.stockbatch.StockBatchService;
import com.inventra.api.core.service.stockbatch.model.request.ConsumeProductRequest;
import com.inventra.api.core.service.stockbatch.model.request.RegisterStockEntryRequest;
import com.inventra.api.infrastructure.exception.BusinessRuleException;
import com.inventra.api.infrastructure.repository.*;
import com.inventra.api.infrastructure.security.KitchenAccessGuard;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext
@EnabledIfEnvironmentVariable(named = "POSTGRES_REDIS_RANKING_TESTS", matches = "true")
class RankingPostgresRedisIntegrationTest {
    // Somente serviços locais descartáveis; nunca lê DB_* ou REDIS_* da aplicação.
    private static final String PREFIX = "inventra:test:ranking:postgres:" + UUID.randomUUID();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",
                () -> "jdbc:postgresql://127.0.0.1:15432/inventra_ranking_test");
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.datasource.username", () -> "ranking_test");
        properties.add("spring.datasource.password", () -> "ranking_test");
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> "12");
        properties.add("spring.data.redis.host", () -> "127.0.0.1");
        properties.add("spring.data.redis.port", () -> "16379");
        properties.add("spring.data.redis.ssl.enabled", () -> "false");
        properties.add("app.redis.ranking.key-prefix", () -> PREFIX);
    }

    @Autowired StockBatchService stock;
    @Autowired RankingService ranking;
    @Autowired KitchenRepository kitchens;
    @Autowired ProductRepository products;
    @Autowired ProfileRepository profiles;
    @Autowired UnitRepository units;
    @Autowired UserRepository users;
    @Autowired StockBatchRepository batches;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor rankingExecutor;
    @MockitoBean KitchenAccessGuard guard;
    private Kitchen kitchen;
    private Product product;
    private final LocalDate date = LocalDate.of(2030, 1, 1);

    @BeforeEach void fixtures() throws Exception {
        // Carrega as procedures/triggers reais sobre o schema descartável criado pelo Hibernate.
        jdbc.execute(new ClassPathResource("db/migration/V002__business_rules.sql")
                .getContentAsString(StandardCharsets.UTF_8));
        jdbc.execute(new ClassPathResource("db/migration/V007__stock_movements.sql")
                .getContentAsString(StandardCharsets.UTF_8));
        jdbc.execute(new ClassPathResource("db/migration/V010__stock_movement_created_at_default.sql")
                .getContentAsString(StandardCharsets.UTF_8));
        kitchen = kitchens.save(Kitchen.builder().name("Ranking PG").active(true)
                .code(UUID.randomUUID().toString().substring(0, 20)).build());
        var profile = profiles.saveAndFlush(Profile.builder()
                .accessType("ranking-" + UUID.randomUUID().toString().substring(0, 12)).build());
        var user = users.saveAndFlush(User.builder().id(UUID.randomUUID()).name("Ranking Test")
                .email("ranking-" + UUID.randomUUID() + "@inventra.test")
                .profile(profile).kitchen(kitchen).active(true).build());
        when(guard.currentUser()).thenReturn(user);
        var unit = units.save(Unit.builder().symbol(UUID.randomUUID().toString().substring(0, 10))
                .description("Teste").build());
        product = products.save(Product.builder().name("Teste").active(true).unit(unit).build());
    }

    @AfterEach void cleanCache() throws Exception {
        rankingExecutor.submit(() -> {}).get(15, TimeUnit.SECONDS);
        if (kitchen != null) redis.delete(PREFIX + ":{" + kitchen.getId() + "}");
    }

    @Test void realProceduresRefreshRankingAndRollbackPreservesIt() {
        var later = stock.registerEntry(request(date.plusDays(1)));
        var first = stock.registerEntry(request(date));
        assertRanking(first.getId(), later.getId());

        stock.consume(first.getId(), BigDecimal.ONE, StockMovementReason.USAGE);
        assertRanking(later.getId());
        stock.restock(first.getId(), BigDecimal.ONE);
        assertRanking(first.getId(), later.getId());

        assertThatThrownBy(() -> stock.consumeProduct(consumeRequest(BigDecimal.TEN)))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(batches.findById(first.getId()).orElseThrow().getCurrentQuantity())
                .isEqualByComparingTo(BigDecimal.ONE);
        assertRanking(first.getId(), later.getId());

        stock.consumeProduct(consumeRequest(BigDecimal.ONE));
        assertRanking(later.getId());
        stock.adjust(later.getId(), BigDecimal.ZERO);
        assertRanking();
    }

    @Test void concurrentCommitsAndCacheLossPreserveAllEligibleBatches() throws Exception {
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Callable<Integer>> tasks = IntStream.range(0, 12)
                    .mapToObj(i -> (Callable<Integer>) () -> stock.registerEntry(request(date.plusDays(i))).getId())
                    .toList();
            for (var result : executor.invokeAll(tasks)) result.get(30, TimeUnit.SECONDS);
        }
        rankingExecutor.submit(() -> {}).get(15, TimeUnit.SECONDS);
        var entries = ranking.top(kitchen.getId(), 100);
        assertThat(entries).hasSize(12);
        assertThat(entries).extracting(RankingEntry::score).isSorted();
        redis.delete(PREFIX + ":{" + kitchen.getId() + "}");
        assertThat(ranking.top(kitchen.getId(), 100)).isEqualTo(entries);
    }

    private RegisterStockEntryRequest request(LocalDate expires) {
        return new RegisterStockEntryRequest(product.getId(), kitchen.getId(), null, "TEST", null,
                BigDecimal.ONE, date.minusDays(1), expires, BigDecimal.ONE);
    }

    private ConsumeProductRequest consumeRequest(BigDecimal quantity) {
        return new ConsumeProductRequest(kitchen.getId(), product.getId(), quantity, StockMovementReason.USAGE);
    }

    private void assertRanking(Integer... ids) {
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(ranking.top(kitchen.getId(), 10)).extracting(RankingEntry::batchId).containsExactly(ids));
    }
}
