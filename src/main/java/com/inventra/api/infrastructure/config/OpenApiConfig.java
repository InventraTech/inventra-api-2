package com.inventra.api.infrastructure.config;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

// Declara o esquema Bearer JWT: faz o Swagger UI mostrar o botão "Authorize" e enviar
// "Authorization: Bearer <token>" nas chamadas. Só afeta a documentação — a regra de acesso
// continua no SecurityConfig (login e register seguem públicos, mesmo com o requisito global).
// Também documenta, em todo endpoint, as respostas de erro que o GlobalExceptionHandler devolve
// (ProblemDetail), sem precisar repetir @ApiResponse em cada método.
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";
    private static final String PROBLEM_SCHEMA = "ProblemDetail";

    @Bean
    public OpenAPI inventraOpenApi() {
        Components components = new Components().addSecuritySchemes(BEARER_SCHEME,
                new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT"));
        components.addSchemas(PROBLEM_SCHEMA, problemDetailSchema());

        return new OpenAPI()
                .info(new Info()
                        .title("Inventra API")
                        .version("1.0")
                        .description("API REST de controle de estoque para cozinhas: produtos, lotes, "
                                + "requisições, inventários e alertas. Autenticação via JWT "
                                + "(use /api/auth/login e informe o token em Authorize)."))
                .components(components)
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }

    @Bean
    public OperationCustomizer errorResponsesCustomizer() {
        return (operation, handlerMethod) -> {
            boolean publicEndpoint = handlerMethod.getBeanType().getSimpleName().equals("AuthController");
            boolean hasInput = operation.getRequestBody() != null
                    || (operation.getParameters() != null && !operation.getParameters().isEmpty());
            boolean hasPathVariable = operation.getParameters() != null
                    && operation.getParameters().stream().anyMatch(p -> "path".equals(p.getIn()));

            if (hasInput) {
                addIfAbsent(operation, "400", "Dados inválidos (validação, JSON malformado ou parâmetro com tipo errado)");
            }
            if (publicEndpoint) {
                addIfAbsent(operation, "401", "Credenciais ou token inválidos");
                addIfAbsent(operation, "429", "Muitas tentativas; aguarde antes de tentar de novo");
            } else {
                addIfAbsent(operation, "401", "Token ausente, inválido ou expirado");
                addIfAbsent(operation, "403", "Sem permissão para este recurso");
                if (hasPathVariable) {
                    addIfAbsent(operation, "404", "Recurso não encontrado");
                }
                if (!handlerMethod.hasMethodAnnotation(org.springframework.web.bind.annotation.GetMapping.class)) {
                    addIfAbsent(operation, "409", "Conflito com o estado atual ou violação de regra de negócio");
                }
            }
            addIfAbsent(operation, "500", "Erro interno inesperado");
            return operation;
        };
    }

    // Formato RFC 7807 devolvido pelo GlobalExceptionHandler; "errors" só aparece nos erros de validação.
    private Schema<?> problemDetailSchema() {
        Schema<?> violation = new ObjectSchema()
                .addProperty("field", new StringSchema().example("name"))
                .addProperty("message", new StringSchema().example("não deve estar em branco"));
        return new ObjectSchema()
                .addProperty("title", new StringSchema().example("Bad Request"))
                .addProperty("status", new IntegerSchema().example(400))
                .addProperty("detail", new StringSchema().example("Um ou mais campos são inválidos."))
                .addProperty("instance", new StringSchema().example("/api/categories"))
                .addProperty("timestamp", new StringSchema().format("date-time"))
                .addProperty("errors", new ArraySchema().items(violation)
                        .description("Violações por campo (apenas em erros de validação)"));
    }

    private void addIfAbsent(io.swagger.v3.oas.models.Operation operation, String status, String description) {
        if (operation.getResponses().containsKey(status)) {
            return;
        }
        operation.getResponses().addApiResponse(status, new ApiResponse()
                .description(description)
                .content(new Content().addMediaType("application/problem+json",
                        new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA)))));
    }
}
