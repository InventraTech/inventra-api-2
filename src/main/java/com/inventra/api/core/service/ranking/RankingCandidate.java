package com.inventra.api.core.service.ranking;

import java.time.LocalDate;

public record RankingCandidate(Integer batchId, LocalDate expirationDate) {
}
