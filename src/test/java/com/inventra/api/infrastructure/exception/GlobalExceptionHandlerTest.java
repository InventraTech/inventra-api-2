package com.inventra.api.infrastructure.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.sql.SQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Prova o requisito "tratamento centralizado de exceções": cada tipo de erro vira o status HTTP certo,
// no formato ProblemDetail (application/problem+json), sem subir o contexto inteiro do Spring.
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @RestController
    static class ThrowingController {

        record Payload(@NotBlank String name, @Size(max = 3) String code) {
        }

        enum Kind { ONE, TWO }

        record EnumPayload(Kind accessType, Integer quantity) {
        }

        @PostMapping("/enum")
        String enumBody(@RequestBody EnumPayload payload) {
            return "ok";
        }

        @PostMapping("/validate")
        String validate(@Valid @RequestBody Payload payload) {
            return "ok";
        }

        @GetMapping("/typed")
        String typed(@RequestParam Integer id) {
            return "ok";
        }

        @GetMapping("/not-found")
        String notFound() {
            throw new ResourceNotFoundException("Produto não encontrado.");
        }

        @GetMapping("/business")
        String business() {
            throw new BusinessRuleException("Regra de negócio violada.");
        }

        @GetMapping("/illegal-argument")
        String illegalArgument() {
            throw new IllegalArgumentException("Argumento inválido.");
        }

        @GetMapping("/too-many")
        String tooMany() {
            throw new TooManyRequestsException("Muitas tentativas.");
        }

        @GetMapping("/denied")
        String denied() {
            throw new AccessDeniedException("negado");
        }

        @GetMapping("/bad-credentials")
        String badCredentials() {
            throw new BadCredentialsException("bad");
        }

        @GetMapping("/disabled")
        String disabled() {
            throw new DisabledException("off");
        }

        @GetMapping("/integrity")
        String integrity() {
            throw new DataIntegrityViolationException("fk violada");
        }

        @GetMapping("/duplicate")
        String duplicate() {
            throw new DataIntegrityViolationException("dup", new SQLException("duplicate key", "23505"));
        }

        @GetMapping("/raise-exception")
        String raiseException() {
            // RAISE EXCEPTION de procedure/trigger no Postgres chega com SQLState P0001
            throw new InvalidDataAccessResourceUsageException("erro",
                    new SQLException("ERROR: Estoque insuficiente no lote\n  Where: PL/pgSQL function fn_validate_stock()", "P0001"));
        }

        @GetMapping("/data-access")
        String dataAccess() {
            throw new InvalidDataAccessResourceUsageException("sql quebrado", new SQLException("boom", "42P01"));
        }

        @GetMapping("/external")
        String external() {
            throw new RestClientException("OpenFoodFacts fora do ar");
        }

        @GetMapping("/image")
        String image() {
            throw new ImageStorageException("cloudinary caiu", new RuntimeException());
        }

        @GetMapping("/io")
        String io() throws IOException {
            throw new IOException("upload truncado");
        }

        @GetMapping("/unexpected")
        String unexpected() {
            throw new IllegalStateException("segredo interno que não pode vazar");
        }
    }

    @Test
    void resourceNotFoundBecomes404() throws Exception {
        mockMvc.perform(get("/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value("Produto não encontrado."))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void businessRuleBecomes409() throws Exception {
        mockMvc.perform(get("/business"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Regra de negócio violada."));
    }

    @Test
    void illegalArgumentBecomes400() throws Exception {
        mockMvc.perform(get("/illegal-argument"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Argumento inválido."));
    }

    @Test
    void bodyValidationBecomes400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"code\":\"toolong\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Um ou mais campos são inválidos."))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[?(@.field=='name')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='code')]").exists());
    }

    @Test
    void malformedJsonBecomes400() throws Exception {
        mockMvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("JSON malformado")));
    }

    @Test
    void missingBodyBecomes400() throws Exception {
        mockMvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Corpo da requisição ausente."));
    }

    @Test
    void invalidEnumValueNamesTheFieldAndTheAcceptedValues() throws Exception {
        mockMvc.perform(post("/enum").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accessType\":\"cachorro\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "Valor inválido 'cachorro' para o campo 'accessType'. Valores aceitos: ONE, TWO."));
    }

    @Test
    void wrongTypeInBodyNamesTheFieldAndTheExpectedType() throws Exception {
        mockMvc.perform(post("/enum").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":\"muitos\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "Valor inválido 'muitos' para o campo 'quantity'. Esperado um número."));
    }

    @Test
    void wrongParameterTypeBecomes400() throws Exception {
        mockMvc.perform(get("/typed").param("id", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Valor inválido 'abc' para o parâmetro 'id'. Esperado um número."));
    }

    @Test
    void missingRequiredParameterBecomes400() throws Exception {
        mockMvc.perform(get("/typed"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unsupportedMethodBecomes405() throws Exception {
        mockMvc.perform(post("/not-found"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    void unsupportedMediaTypeBecomes415() throws Exception {
        mockMvc.perform(post("/validate").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void unknownRouteBecomes404() throws Exception {
        mockMvc.perform(get("/rota-que-nao-existe"))
                .andExpect(status().isNotFound());
    }

    @Test
    void tooManyRequestsBecomes429() throws Exception {
        mockMvc.perform(get("/too-many"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value("Muitas tentativas."));
    }

    @Test
    void accessDeniedBecomes403() throws Exception {
        mockMvc.perform(get("/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Você não tem permissão para acessar este recurso."));
    }

    @Test
    void disabledAccountBecomes403() throws Exception {
        mockMvc.perform(get("/disabled"))
                .andExpect(status().isForbidden());
    }

    @Test
    void badCredentialsBecomes401WithoutRevealingWhichFieldWasWrong() throws Exception {
        mockMvc.perform(get("/bad-credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("E-mail ou senha inválidos."));
    }

    @Test
    void dataIntegrityViolationBecomes409WithoutLeakingSql() throws Exception {
        mockMvc.perform(get("/integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A operação viola uma restrição de integridade dos dados."));
    }

    @Test
    void uniqueViolationBecomes409WithDuplicateMessage() throws Exception {
        mockMvc.perform(get("/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Já existe um registro")));
    }

    @Test
    void unsupportedMethodListsTheAcceptedOnes() throws Exception {
        mockMvc.perform(post("/not-found"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Métodos aceitos: GET")));
    }

    @Test
    void procedureRaiseExceptionBecomes409WithTheProcedureMessage() throws Exception {
        mockMvc.perform(get("/raise-exception"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Estoque insuficiente no lote"));
    }

    @Test
    void otherDatabaseErrorsBecome500WithGenericMessage() throws Exception {
        mockMvc.perform(get("/data-access"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Erro interno no servidor."));
    }

    @Test
    void externalServiceFailureBecomes502() throws Exception {
        mockMvc.perform(get("/external"))
                .andExpect(status().isBadGateway());
    }

    @Test
    void imageStorageFailureBecomes502() throws Exception {
        mockMvc.perform(get("/image"))
                .andExpect(status().isBadGateway());
    }

    @Test
    void ioErrorBecomes400() throws Exception {
        mockMvc.perform(get("/io"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unexpectedExceptionBecomes500AndNeverLeaksTheInternalMessage() throws Exception {
        mockMvc.perform(get("/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Erro interno no servidor."))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("segredo"))));
    }
}
