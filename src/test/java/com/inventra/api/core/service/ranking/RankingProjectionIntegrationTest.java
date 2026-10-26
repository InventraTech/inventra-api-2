package com.inventra.api.core.service.ranking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.product.Product;
import com.inventra.api.core.domain.stock.enums.StockMovementReason;
import com.inventra.api.core.domain.unit.Unit;
import com.inventra.api.core.domain.stock.StockBatch;
import com.inventra.api.core.domain.stock.enums.StockBatchStatus;
import com.inventra.api.core.service.stockbatch.StockBatchService;
import com.inventra.api.core.service.stockbatch.model.request.ConsumeProductRequest;
import com.inventra.api.core.service.stockbatch.model.request.RegisterStockEntryRequest;
import com.inventra.api.infrastructure.redis.ranking.RedisExpirationRanking;
import com.inventra.api.infrastructure.repository.*;
import com.inventra.api.infrastructure.security.KitchenAccessGuard;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ranking_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class RankingProjectionIntegrationTest {
    @Autowired RankingProjection projection;
    @Autowired StockBatchService stock;
    @Autowired KitchenRepository kitchens;
    @Autowired StockBatchRepository batches;
    @Autowired ProductRepository products;
    @Autowired UnitRepository units;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor rankingExecutor;
    @MockitoBean RedisExpirationRanking redis;
    @MockitoBean KitchenAccessGuard guard;
    private Kitchen kitchen;
    private Product product;
    private final LocalDate date = LocalDate.of(2030, 1, 1);

    @AfterEach void awaitProjectionTasks() throws Exception {
        rankingExecutor.submit(() -> {}).get(15, TimeUnit.SECONDS);
    }

    @BeforeEach void fixtures() {
        kitchen = kitchens.save(Kitchen.builder().name("Ranking test")
                .code(UUID.randomUUID().toString().substring(0, 20)).active(true).build());
        var unit = units.save(Unit.builder().symbol(UUID.randomUUID().toString().substring(0, 10))
                .description("Teste").build());
        product = products.save(Product.builder().name("Teste").active(true).unit(unit).build());
    }

    @Test void excludesIneligibleBatchesAndOtherKitchensAndOrdersTies() {
        var later = batch(kitchen, date.plusDays(2), StockBatchStatus.ACTIVE, BigDecimal.ONE);
        var first = batch(kitchen, date, StockBatchStatus.ACTIVE, BigDecimal.ONE);
        var tied = batch(kitchen, date, StockBatchStatus.ACTIVE, BigDecimal.ONE);
        batch(kitchen, null, StockBatchStatus.ACTIVE, BigDecimal.ONE);
        batch(kitchen, date.minusDays(1), StockBatchStatus.CANCELLED, BigDecimal.ONE);
        batch(kitchen, date.minusDays(1), StockBatchStatus.EXPIRED, BigDecimal.ONE);
        batch(kitchen, date.minusDays(1), StockBatchStatus.WRITTEN_OFF, BigDecimal.ONE);
        batch(kitchen, date.minusDays(1), StockBatchStatus.ACTIVE, BigDecimal.ZERO);
        var other = kitchens.save(Kitchen.builder().name("Outra").active(true)
                .code(UUID.randomUUID().toString().substring(0, 20)).build());
        batch(other, date.minusDays(1), StockBatchStatus.ACTIVE, BigDecimal.ONE);
        assertThat(projection.rebuild(kitchen.getId(), 2)).extracting(RankingEntry::batchId)
                .containsExactly(first.getId(), tied.getId());
        verify(redis).replace(eq(kitchen.getId()), argThat(rows -> rows.size() == 3
                && rows.stream().anyMatch(row -> row.batchId().equals(later.getId()))));
    }

    @Test void committedEntryAndAdjustmentUpdateRanking() {
        var created = stock.registerEntry(request());
        await().untilAsserted(() -> verify(redis).replace(eq(kitchen.getId()), argThat(rows -> rows.size() == 1
                && rows.getFirst().batchId().equals(created.getId()))));
        clearInvocations(redis);
        stock.adjust(created.getId(), BigDecimal.ZERO);
        await().untilAsserted(() -> verify(redis).replace(kitchen.getId(), List.of()));
        clearInvocations(redis);
        stock.adjust(created.getId(), BigDecimal.ONE);
        await().untilAsserted(() -> verify(redis).replace(eq(kitchen.getId()), argThat(rows -> rows.size() == 1)));
    }

    @Test void rollbackDoesNotPublishUncommittedStock() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            stock.registerEntry(request());
            status.setRollbackOnly();
        });
        assertThat(batches.findByKitchenId(kitchen.getId())).isEmpty();
        verifyNoInteractions(redis);
    }

    @Test void invalidQuantitiesCannotMutateStockOrPublishRanking() {
        var row = batch(kitchen, date, StockBatchStatus.ACTIVE, BigDecimal.ONE);
        assertThatThrownBy(() -> stock.adjust(row.getId(), BigDecimal.valueOf(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stock.consumeProduct(new ConsumeProductRequest(
                kitchen.getId(), product.getId(), BigDecimal.ZERO, StockMovementReason.USAGE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(batches.findById(row.getId()).orElseThrow().getCurrentQuantity()).isEqualByComparingTo(BigDecimal.ONE);
        verifyNoInteractions(redis);
    }

    @Test void cacheMissRechecksCacheAfterLockInsteadOfRebuildingAgain() {
        var cached = List.of(new RankingEntry(1, 99, date, date.toEpochDay()));
        when(redis.top(kitchen.getId(), 10)).thenReturn(java.util.Optional.of(cached));
        assertThat(projection.ensure(kitchen.getId(), 10)).isEqualTo(cached);
        verify(redis, never()).replace(anyInt(), anyList());
    }

    @Test void redisOutageDoesNotReportCommittedStockAsFailed() {
        doThrow(new RedisConnectionFailureException("test offline")).when(redis).replace(anyInt(), anyList());
        var created = stock.registerEntry(request());
        assertThat(batches.existsById(created.getId())).isTrue();
    }

    @Test void slowProjectionDoesNotKeepTheStockRequestOrItsConnectionOpen() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return null;
        }).when(redis).replace(eq(kitchen.getId()), anyList());
        try (var requests = Executors.newSingleThreadExecutor()) {
            var response = requests.submit(() -> stock.registerEntry(request()));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(response.get(2, TimeUnit.SECONDS).getId()).isNotNull();
            } finally {
                release.countDown();
            }
        }
    }

    @Test void rebuildsSerializeAcrossThreadsAndReadSnapshotAfterLock() throws Exception {
        var row = batch(kitchen, date, StockBatchStatus.ACTIVE, BigDecimal.ONE);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var startedSecond = new CountDownLatch(1);
        var calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            }
            return null;
        }).when(redis).replace(eq(kitchen.getId()), anyList());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> projection.rebuild(kitchen.getId(), 10));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> {
                    startedSecond.countDown();
                    return projection.rebuild(kitchen.getId(), 10);
                });
                assertThat(startedSecond.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                row.setCurrentQuantity(BigDecimal.ZERO);
                batches.saveAndFlush(row);
                release.countDown();
                assertThat(first.get(10, TimeUnit.SECONDS)).hasSize(1);
                assertThat(second.get(10, TimeUnit.SECONDS)).isEmpty();
                verify(redis).replace(kitchen.getId(), List.of());
            } finally {
                release.countDown();
            }
        }
    }

    private RegisterStockEntryRequest request() {
        return new RegisterStockEntryRequest(product.getId(), kitchen.getId(), null, "TEST", null,
                BigDecimal.ONE, date.minusDays(1), date, BigDecimal.ONE);
    }

    private StockBatch batch(Kitchen owner, LocalDate expires, StockBatchStatus status, BigDecimal quantity) {
        return batches.save(StockBatch.builder().kitchen(owner).product(product).batchNumber("T")
                .initialQuantity(BigDecimal.ONE).currentQuantity(quantity).entryDate(date.minusDays(1))
                .expirationDate(expires).unitPrice(BigDecimal.ONE).status(status).build());
    }
}
