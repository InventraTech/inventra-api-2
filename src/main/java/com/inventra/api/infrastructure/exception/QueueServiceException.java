package com.inventra.api.infrastructure.exception;

public class QueueServiceException extends RuntimeException {
    public QueueServiceException(String message) {
        super(message);
    }

    public QueueServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
