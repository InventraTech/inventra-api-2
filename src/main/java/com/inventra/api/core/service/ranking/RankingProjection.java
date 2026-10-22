package com.inventra.api.core.service.ranking;

import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.redis.ranking.RedisExpirationRanking;
import com.inventra.api.infrastructure.repository.KitchenRepository;
import com.inventra.api.infrastructure.repository.StockBatchRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RankingProjection {
    private final KitchenRepository kitchens;
    private final StockBatchRepository batches;
    private final RedisExpirationRanking redis;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 15)
    public List<RankingEntry> ensure(Integer kitchenId, int limit) {
        kitchens.lockForRanking(kitchenId)
                .orElseThrow(() -> new ResourceNotFoundException("Cozinha não encontrada."));
        // Outra instância pode ter reconstruído enquanto aguardávamos o lock.
        return redis.top(kitchenId, limit).orElseGet(() -> publishSnapshot(kitchenId, limit));
    }

    // Serializa reconstruções entre instâncias. O snapshot é lido APÓS o lock.
    // REQUIRES_NEW evita reutilizar o contexto da transação já confirmada pelo listener.
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 15)
    public List<RankingEntry> rebuild(Integer kitchenId, int limit) {
        kitchens.lockForRanking(kitchenId)
                .orElseThrow(() -> new ResourceNotFoundException("Cozinha não encontrada."));
        return publishSnapshot(kitchenId, limit);
    }

    private List<RankingEntry> publishSnapshot(Integer kitchenId, int limit) {
        var snapshot = batches.findRankingCandidates(kitchenId);
        redis.replace(kitchenId, snapshot);
        var ordered = snapshot.stream().sorted(Comparator.comparing(RankingCandidate::expirationDate)
                .thenComparing(RankingCandidate::batchId)).limit(limit).toList();
        return IntStream.range(0, ordered.size()).mapToObj(i -> {
            var batch = ordered.get(i);
            return new RankingEntry(i + 1, batch.batchId(), batch.expirationDate(),
                    batch.expirationDate().toEpochDay());
        }).toList();
    }
}
