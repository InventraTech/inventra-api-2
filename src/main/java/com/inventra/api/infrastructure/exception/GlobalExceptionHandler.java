package com.inventra.api.infrastructure.exception;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String RAISE_EXCEPTION_SQL_STATE = "P0001";

    @ExceptionHandler(RankingUnavailableException.class)
    public ProblemDetail handleRankingUnavailable(RankingUnavailableException ex) {
        log.warn("Falha ao consultar ranking de validade", ex);
        return buildProblem(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", ex.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleResourceNotFound(ResourceNotFoundException ex) {
        return buildProblem(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage());
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ProblemDetail handleBusinessRule(BusinessRuleException ex) {
        return buildProblem(HttpStatus.CONFLICT, "Conflict", ex.getMessage());
    }

    @ExceptionHandler(ApiProblemException.class)
    public ProblemDetail handleApiProblem(ApiProblemException ex) {
        ProblemDetail problem = buildProblem(ex.getStatus(), ex.getStatus().getReasonPhrase(), ex.getMessage());
        problem.setProperty("code", ex.getCode());
        ex.getProperties().forEach(problem::setProperty);
        return problem;
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ProblemDetail handleTooManyRequests(TooManyRequestsException ex) {
        return buildProblem(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", ex.getMessage());
    }

    @ExceptionHandler(DisabledException.class)
    public ProblemDetail handleDisabled(DisabledException ex) {
        return buildProblem(HttpStatus.FORBIDDEN, "Forbidden", "Conta desativada. Contate o supervisor da sua cozinha.");
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

    // ===== Erros do próprio Spring MVC: sem estes handlers, o handler genérico os devolveria como 500 =====

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleNotReadable(HttpMessageNotReadableException ex) {
        return buildProblem(HttpStatus.BAD_REQUEST, "Bad Request", describeUnreadableBody(ex));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        StringBuilder detail = new StringBuilder("Valor inválido '").append(ex.getValue())
                .append("' para o parâmetro '").append(ex.getName()).append("'.");
        appendExpectedType(detail, ex.getRequiredType());
        return buildProblem(HttpStatus.BAD_REQUEST, "Bad Request", detail.toString());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParameter(MissingServletRequestParameterException ex) {
        return buildProblem(HttpStatus.BAD_REQUEST, "Bad Request",
                "Parâmetro obrigatório ausente: '" + ex.getParameterName() + "'.");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ProblemDetail handleMissingPart(MissingServletRequestPartException ex) {
        return buildProblem(HttpStatus.BAD_REQUEST, "Bad Request",
                "Parte obrigatória ausente: '" + ex.getRequestPartName() + "'.");
    }

    @ExceptionHandler(PropertyReferenceException.class)
    public ProblemDetail handlePropertyReference(PropertyReferenceException ex) {
        return buildProblem(HttpStatus.BAD_REQUEST, "Bad Request",
                "Campo de ordenação inválido: '" + ex.getPropertyName() + "'.");
    }

    // Uso inválido da API de dados vindo do cliente, ex.: ordenação (?sort=) por campo que não existe.
    @ExceptionHandler(InvalidDataAccessApiUsageException.class)
    public ProblemDetail handleInvalidDataAccessApiUsage(InvalidDataAccessApiUsageException ex) {
        log.warn("Parâmetros inválidos para a consulta", ex);
        return buildProblem(HttpStatus.BAD_REQUEST, "Bad Request", "Parâmetros de consulta inválidos.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        return buildProblem(HttpStatus.CONTENT_TOO_LARGE, "Content Too Large", "O arquivo enviado excede o tamanho máximo de 5MB.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        String detail = "Método " + ex.getMethod() + " não é suportado nesse endpoint.";
        if (ex.getSupportedHttpMethods() != null && !ex.getSupportedHttpMethods().isEmpty()) {
            detail += " Métodos aceitos: " + ex.getSupportedHttpMethods().stream()
                    .map(Object::toString).collect(Collectors.joining(", ")) + ".";
        }
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .headers(ex.getHeaders())
                .body(buildProblem(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed", detail));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        String detail = ex.getContentType() == null
                ? "Content-Type ausente."
                : "Content-Type '" + ex.getContentType() + "' não suportado.";
        if (!ex.getSupportedMediaTypes().isEmpty()) {
            detail += " Tipos aceitos: " + ex.getSupportedMediaTypes().stream()
                    .map(Object::toString).collect(Collectors.joining(", ")) + ".";
        }
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .headers(ex.getHeaders())
                .body(buildProblem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported Media Type", detail));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException ex) {
        return buildProblem(HttpStatus.NOT_FOUND, "Not Found", "Recurso não encontrado.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Violação de integridade no banco de dados", ex);
        SQLException sqlException = findSqlException(ex);
        String sqlState = sqlException == null ? null : sqlException.getSQLState();
        String detail = switch (sqlState == null ? "" : sqlState) {
            case "23505" -> "Já existe um registro com os mesmos dados (valor duplicado em campo único).";
            case "23503" -> "A operação referencia um registro que não existe ou que ainda é usado por outros dados.";
            case "23502" -> "Um campo obrigatório não foi informado.";
            default -> "A operação viola uma restrição de integridade dos dados.";
        };
        return buildProblem(HttpStatus.CONFLICT, "Conflict", detail);
    }

    // RAISE EXCEPTION das procedures/triggers (V002__business_rules.sql) chega com SQLState P0001:
    // é regra de negócio do banco, então devolve 409 com a mensagem da procedure.
    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail handleDataAccess(DataAccessException ex) {
        SQLException sqlException = findSqlException(ex);
        if (sqlException != null && RAISE_EXCEPTION_SQL_STATE.equals(sqlException.getSQLState())) {
            return buildProblem(HttpStatus.CONFLICT, "Conflict", raisedMessage(sqlException));
        }
        log.error("Erro de acesso ao banco de dados", ex);
        return buildProblem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "Erro interno no servidor.");
    }

    @ExceptionHandler({RedisConnectionFailureException.class, RedisSystemException.class})
    public ProblemDetail handleRedisUnavailable(RuntimeException ex) {
        log.warn("Redis indisponível errorType={}", ex.getClass().getSimpleName(), ex);
        return buildProblem(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
                "Serviço de processamento indisponível. Tente novamente em instantes.");
    }

    @ExceptionHandler(QueueServiceException.class)
    public ProblemDetail handleQueueService(QueueServiceException ex) {
        log.warn("Falha no serviço de fila errorType={}", ex.getClass().getSimpleName(), ex);
        return buildProblem(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
                "Serviço de processamento indisponível. Tente novamente em instantes.");
    }

    @ExceptionHandler(RestClientException.class)
    public ProblemDetail handleRestClientException(RestClientException ex) {
        log.warn("Falha ao chamar serviço externo", ex);
        return buildProblem(HttpStatus.BAD_GATEWAY, "Bad Gateway", "Falha ao consultar um serviço externo.");
    }

    @ExceptionHandler(ImageStorageException.class)
    public ProblemDetail handleImageStorage(ImageStorageException ex) {
        log.warn("Falha no serviço de imagens", ex);
        return buildProblem(HttpStatus.BAD_GATEWAY, "Bad Gateway", "Falha ao processar a imagem. Tente novamente.");
    }

    // Costuma vir de MultipartFile.getBytes() quando o upload foi truncado ou o temp file
    // ficou inacessível. Do ponto de vista do cliente, a requisição chegou incompleta — 400.
    @ExceptionHandler(IOException.class)
    public ProblemDetail handleIO(IOException ex) {
        log.warn("Falha de I/O ao ler a requisição", ex);
        return buildProblem(HttpStatus.BAD_REQUEST, "Bad Request", "Falha ao ler o arquivo enviado. Tente novamente.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneric(Exception ex) {
        if (ex instanceof ErrorResponse errorResponse) {
            return ResponseEntity.status(errorResponse.getStatusCode())
                    .headers(errorResponse.getHeaders())
                    .body(errorResponse.getBody());
        }
        log.error("Erro não tratado", ex);
        return ResponseEntity.internalServerError().body(
                buildProblem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "Erro interno no servidor."));
    }

    private ProblemDetail buildProblem(HttpStatus status, String title, String detail) {
        return ProblemDetailFactory.build(status, title, detail);
    }

    // Usa a causa do Jackson para dizer qual campo/valor quebrou o JSON em vez de uma frase genérica.
    private static String describeUnreadableBody(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof MismatchedInputException mismatch) {
            String field = jsonPath(mismatch);
            if (field.isEmpty()) {
                return "Corpo da requisição com formato inválido.";
            }
            StringBuilder detail = new StringBuilder();
            if (mismatch instanceof InvalidFormatException invalid) {
                detail.append("Valor inválido '").append(invalid.getValue()).append("' para o campo '")
                        .append(field).append("'.");
            } else {
                detail.append("Tipo inválido para o campo '").append(field).append("'.");
            }
            appendExpectedType(detail, mismatch.getTargetType());
            return detail.toString();
        }
        if (cause instanceof JacksonException) {
            return "JSON malformado. Verifique vírgulas, aspas e chaves do corpo da requisição.";
        }
        return "Corpo da requisição ausente.";
    }

    private static String jsonPath(JacksonException ex) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference reference : ex.getPath()) {
            if (reference.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getPropertyName());
            } else {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    private static void appendExpectedType(StringBuilder detail, Class<?> type) {
        if (type == null) {
            return;
        }
        if (type.isEnum()) {
            detail.append(" Valores aceitos: ")
                    .append(Arrays.stream(type.getEnumConstants()).map(Object::toString).collect(Collectors.joining(", ")))
                    .append('.');
        } else if (Number.class.isAssignableFrom(type) || type.isPrimitive() && type != boolean.class) {
            detail.append(" Esperado um número.");
        } else if (type == Boolean.class || type == boolean.class) {
            detail.append(" Esperado true ou false.");
        } else if (type == LocalDate.class) {
            detail.append(" Esperado uma data no formato AAAA-MM-DD.");
        } else if (type == LocalDateTime.class || type == Instant.class || type == OffsetDateTime.class) {
            detail.append(" Esperado data e hora no formato ISO-8601 (ex.: 2026-10-10T14:30:00).");
        } else if (type == UUID.class) {
            detail.append(" Esperado um UUID.");
        }
    }

    private static SQLException findSqlException(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException;
            }
        }
        return null;
    }

    // O driver formata como "ERROR: <mensagem>\n  Where: PL/pgSQL function ..."; só a mensagem interessa ao cliente.
    private static String raisedMessage(SQLException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        int lineBreak = message.indexOf('\n');
        if (lineBreak >= 0) {
            message = message.substring(0, lineBreak);
        }
        return message.replaceFirst("^ERROR:\\s*", "").trim();
    }

    private record FieldViolation(String field, String message) {
    }
}
