package com.inventra.api.infrastructure.exception;

import java.util.Map;

import org.springframework.http.HttpStatus;

import lombok.Getter;

// Erro com status e "code" próprios no ProblemDetail, para o app tratar um caso específico sem depender
// do texto da mensagem (ex.: REGISTRATION_REQUIRED no login com Google).
@Getter
public class ApiProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties;

    public ApiProblemException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiProblemException(HttpStatus status, String code, String message, Map<String, Object> properties) {
        super(message);
        this.status = status;
        this.code = code;
        this.properties = properties;
    }
}
