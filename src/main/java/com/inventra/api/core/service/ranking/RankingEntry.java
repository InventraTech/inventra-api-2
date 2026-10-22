package com.inventra.api.core.service.ranking;

import java.time.LocalDate;

public record RankingEntry(int position, Integer batchId, LocalDate expirationDate, long score) {
}
