package com.inventra.api.infrastructure.exception;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.ErrorResponse;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.client.RestClientException;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleResourceNotFound(ResourceNotFoundException ex) {
        return buildProblem(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage());
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ProblemDetail handleBusinessRule(BusinessRuleException ex) {
        return buildProblem(HttpStatus.CONFLICT, "Conflict", ex.getMessage());
    }

    @ExceptionHandler(DisabledException.class)
    public ProblemDetail handleDisabled(DisabledException ex) {
        return buildProblem(HttpStatus.FORBIDDEN, "Forbidden", "Conta desativada. Contate um administrador.");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthenticationFailure(AuthenticationException ex) {
        return buildProblem(HttpStatus.UNAUTHORIZED, "Unauthorized", "E-mail ou senha inválidos.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return buildProblem(HttpStatus.FORBIDDEN, "Forbidden", "Você não tem permissão para acessar este recurso.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail problem = buildProblem(HttpStatus.BAD_REQUEST, "Bad Request", "Um ou mais campos são inválidos.");
        List<FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new FieldViolation(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return buildProblem(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail handleMalformedRequest(Exception ex) {
        return buildProblem(HttpStatus.BAD_REQUEST, "Bad Request", "Conteúdo ou identificador da requisição inválido.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Violação de integridade no banco de dados", ex);
        return buildProblem(HttpStatus.CONFLICT, "Conflict", "A operação viola uma restrição de integridade dos dados.");
    }

    @ExceptionHandler(RestClientException.class)
    public ProblemDetail handleRestClientException(RestClientException ex) {
        log.warn("Falha ao chamar serviço externo", ex);
        return buildProblem(HttpStatus.BAD_GATEWAY, "Bad Gateway", "Falha ao consultar um serviço externo.");
    }

    @ExceptionHandler({RedisConnectionFailureException.class, RedisSystemException.class})
    public ProblemDetail handleRedisUnavailable(RuntimeException ex) {
        log.warn("Redis indisponível errorType={}", ex.getClass().getSimpleName());
        return buildProblem(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
                "Serviço de processamento indisponível. Tente novamente em instantes.");
    }

    @ExceptionHandler(QueueServiceException.class)
    public ProblemDetail handleQueueService(QueueServiceException ex) {
        log.warn("Falha no serviço de fila errorType={}", ex.getClass().getSimpleName());
        return buildProblem(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
                "Serviço de processamento indisponível. Tente novamente em instantes.");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        if (ex instanceof ErrorResponse errorResponse) {
            return errorResponse.getBody();
        }
        log.error("Erro não tratado", ex);
        return buildProblem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "Erro interno no servidor.");
    }

    private ProblemDetail buildProblem(HttpStatus status, String title, String detail) {
        return ProblemDetailFactory.build(status, title, detail);
    }

    private record FieldViolation(String field, String message) {
    }
}
