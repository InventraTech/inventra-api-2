package com.inventra.api.core.service.ranking;

import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.inventra.api.infrastructure.exception.RankingUnavailableException;
import com.inventra.api.infrastructure.redis.ranking.RedisExpirationRanking;
import com.inventra.api.infrastructure.security.KitchenAccessGuard;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RankingService {
    private final KitchenAccessGuard accessGuard;
    private final RedisExpirationRanking redis;
    private final RankingProjection projection;

    public List<RankingEntry> top(Integer kitchenId, int limit) {
        if (kitchenId == null || kitchenId < 1 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Informe kitchenId positivo e limit entre 1 e 100.");
        }
        accessGuard.assertAccess(kitchenId);
        try {
            return redis.top(kitchenId, limit).orElseGet(() -> projection.ensure(kitchenId, limit));
        } catch (DataAccessException ex) {
            throw new RankingUnavailableException(ex);
        }
    }
}
