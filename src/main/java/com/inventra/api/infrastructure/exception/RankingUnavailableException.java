package com.inventra.api.infrastructure.exception;

public class RankingUnavailableException extends RuntimeException {
    public RankingUnavailableException(Throwable cause) {
        super("Ranking temporariamente indisponível.", cause);
    }
}
