package com.inventra.api.infrastructure.database;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.profile.Profile;
import com.inventra.api.core.domain.user.User;
import com.inventra.api.infrastructure.client.openfoodfacts.OpenFoodFactsClient;
import com.inventra.api.infrastructure.repository.KitchenRepository;
import com.inventra.api.infrastructure.repository.ProfileRepository;
import com.inventra.api.infrastructure.repository.UserRepository;

// Base dos testes contra um Postgres de verdade (Testcontainers). O H2 dos outros testes não executa
// PL/pgSQL: aqui o schema nasce das migrations do Flyway, como em produção, com ddl-auto=validate conferindo
// as entidades contra ele, e as procedures e triggers rodam de fato. Sem Docker, os testes são pulados.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Testcontainers(disabledWithoutDocker = true)
@Transactional
abstract class PostgresIntegrationTest {

    private static final String RAW_PASSWORD = "SenhaForte123";

    // um container para todas as classes filhas: o contexto do Spring também é reaproveitado entre elas.
    // Sem @Container de propósito: o JUnit pararia o container ao fim da primeira classe filha e o contexto
    // em cache ficaria apontando para ele. Iniciado uma vez por JVM; o Ryuk do Testcontainers remove no fim.
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired protected MockMvc mockMvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private ProfileRepository profileRepository;
    @Autowired private KitchenRepository kitchenRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockitoBean private OpenFoodFactsClient openFoodFactsClient;

    protected final ObjectMapper objectMapper = new ObjectMapper();

    protected Kitchen kitchen;
    private String auth;

    @BeforeEach
    void logInAsSupervisor() throws Exception {
        kitchen = kitchenRepository.saveAndFlush(Kitchen.builder()
                .name("Cozinha Postgres").code("PG-" + UUID.randomUUID().toString().substring(0, 8)).build());
        Profile supervisor = profileRepository.findByAccessType("supervisor")
                .orElseGet(() -> profileRepository.saveAndFlush(Profile.builder().accessType("supervisor").build()));
        User user = userRepository.saveAndFlush(User.builder()
                .id(UUID.randomUUID()).name("Supervisor")
                .email("sup-" + UUID.randomUUID() + "@inventra.com")
                .passwordHash(passwordEncoder.encode(RAW_PASSWORD))
                .profile(supervisor).kitchen(kitchen).active(true).build());

        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(user.getEmail(), RAW_PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        auth = "Bearer " + objectMapper.readTree(body).get("token").asText();
    }

    protected ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, auth);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    protected JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    protected int createdId(String url, String body) throws Exception {
        return json(send(post(url), body).andExpect(status().isCreated())).get("id").asInt();
    }
}
