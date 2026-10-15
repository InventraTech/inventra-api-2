package com.inventra.api.core.service.ranking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.access.AccessDeniedException;

import com.inventra.api.infrastructure.exception.RankingUnavailableException;
import com.inventra.api.infrastructure.redis.ranking.RedisExpirationRanking;
import com.inventra.api.infrastructure.security.KitchenAccessGuard;

class RankingServiceTest {
    private final KitchenAccessGuard guard = mock(KitchenAccessGuard.class);
    private final RedisExpirationRanking redis = mock(RedisExpirationRanking.class);
    private final RankingProjection projection = mock(RankingProjection.class);
    private final RankingService service = new RankingService(guard, redis, projection);

    @Test void cacheHitPreservesOrderAndScores() {
        var expected = List.of(new RankingEntry(1, 3, LocalDate.ofEpochDay(20000), 20000));
        when(redis.top(1, 10)).thenReturn(Optional.of(expected));
        assertThat(service.top(1, 10)).isEqualTo(expected);
        verify(guard).assertAccess(1);
        verifyNoInteractions(projection);
    }

    @Test void missingCacheIsRebuiltButEmptyRankingIsCached() {
        when(redis.top(1, 10)).thenReturn(Optional.empty(), Optional.of(List.of()));
        when(projection.ensure(1, 10)).thenReturn(List.of());
        assertThat(service.top(1, 10)).isEmpty();
        assertThat(service.top(1, 10)).isEmpty();
        verify(projection, times(1)).ensure(1, 10);
    }

    @Test void deniesOtherKitchenBeforeAnyRedisOrDatabaseAccess() {
        doThrow(new AccessDeniedException("Negado")).when(guard).assertAccess(2);
        assertThatThrownBy(() -> service.top(2, 10)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(redis, projection);
    }

    @Test void validatesBoundsBeforeAccess() {
        assertThatThrownBy(() -> service.top(null, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.top(0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.top(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.top(1, 101)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(guard, redis, projection);
    }

    @Test void unavailableRedisProducesExplicitServiceFailure() {
        when(redis.top(1, 10)).thenThrow(new RedisConnectionFailureException("offline"));
        assertThatThrownBy(() -> service.top(1, 10)).isInstanceOf(RankingUnavailableException.class);
    }
}
