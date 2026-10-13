package com.inventra.api.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.profile.AccessType;
import com.inventra.api.core.domain.profile.Profile;
import com.inventra.api.core.domain.user.User;
import com.inventra.api.core.service.auth.model.request.LoginRequest;
import com.inventra.api.core.service.auth.model.request.RegisterRequest;
import com.inventra.api.core.service.auth.model.response.LoginResponse;
import com.inventra.api.core.service.productqueue.model.ProductRegistrationJob;
import com.inventra.api.infrastructure.client.openfoodfacts.OpenFoodFactsClient;
import com.inventra.api.infrastructure.redis.queue.RedisProductRegistrationQueue;
import com.inventra.api.infrastructure.repository.KitchenRepository;
import com.inventra.api.infrastructure.repository.ProfileRepository;
import com.inventra.api.infrastructure.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class JwtAuthenticationIntegrationTest {

    private static final String RAW_PASSWORD = "SenhaForte123";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // Evita instanciar o RestClient real (HttpClient falha em ambientes sandboxed sem loopback).
    @MockitoBean
    private OpenFoodFactsClient openFoodFactsClient;

    @MockitoBean
    private RedisProductRegistrationQueue productRegistrationQueue;

    private String email;

    private User user;

    @Autowired
    private KitchenRepository kitchenRepository;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"comprador", "estoquista"})
    void restrictedRolesCannotCreateProductsThroughEitherEndpoint(String role) throws Exception {
        Profile roleProfile = profileRepository.findByAccessType(role).orElseGet(
                () -> profileRepository.saveAndFlush(Profile.builder().accessType(role).build()));
        user.setProfile(roleProfile);
        userRepository.saveAndFlush(user);
        String token = login(email, RAW_PASSWORD);
        for (String url : java.util.List.of("/api/products", "/api/products/barcode-registrations")) {
            mockMvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Arroz\",\"unitId\":1,\"barcode\":\"7891234567890\"}"))
                    .andExpect(status().isForbidden());
        }
        org.mockito.Mockito.verifyNoInteractions(productRegistrationQueue);
    }

    @Test
    void supervisorCanEnqueueAndReachNormalProductCreation() throws Exception {
        ensureProfile("supervisor");
        user.setProfile(profileRepository.findByAccessType("supervisor").orElseThrow());
        userRepository.saveAndFlush(user);
        String token = login(email, RAW_PASSWORD);
        mockMvc.perform(post("/api/products/barcode-registrations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Arroz\",\"unitId\":1,\"barcode\":\"7891234567890\"}"))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/products").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void barcodeRegistrationRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/products/barcode-registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Arroz\",\"unitId\":1,\"barcode\":\"7891234567890\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/products/barcode-registrations/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenEnqueuesRegistrationWithAuthenticatedUser() throws Exception {
        ensureProfile("supervisor");
        user.setProfile(profileRepository.findByAccessType("supervisor").orElseThrow());
        userRepository.saveAndFlush(user);
        String token = login(email, RAW_PASSWORD);
        mockMvc.perform(post("/api/products/barcode-registrations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Arroz\",\"unitId\":1,\"barcode\":\"7891234567890\"}"))
                .andExpect(status().isAccepted());
        var captor = ArgumentCaptor.forClass(ProductRegistrationJob.class);
        verify(productRegistrationQueue).enqueue(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(user.getId());
    }

    @BeforeEach
    void seedUser() {
        // saveAndFlush: o login roda numa sessão JPA separada (por requisição via MockMvc),
        // que só enxerga o que já foi enviado ao banco na transação compartilhada do teste.
        // estoquista (e não supervisor): os testes de register contam os perfis "supervisor" criados
        Profile profile = profileRepository.findByAccessType("estoquista")
                .orElseGet(() -> profileRepository.saveAndFlush(Profile.builder().accessType("estoquista").build()));

        email = "test-" + UUID.randomUUID() + "@inventra.com";
        user = userRepository.saveAndFlush(User.builder()
                .id(UUID.randomUUID())
                .name("Usuário de Teste")
                .email(email)
                .passwordHash(passwordEncoder.encode(RAW_PASSWORD))
                .profile(profile)
                .active(true)
                .build());
    }

    @Test
    void validTokenGrantsAccessToProtectedEndpoint() throws Exception {
        String token = login(email, RAW_PASSWORD);

        mockMvc.perform(get("/api/users/" + user.getId()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    // RBAC: listar usuários é exclusivo do supervisor.
    @Test
    void nonSupervisorCannotListUsers() throws Exception {
        String token = login(email, RAW_PASSWORD);

        mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    // Antes, qualquer usuário trocava o próprio kitchenId e passava a acessar outra cozinha.
    @Test
    void userCannotAssignThemselvesToAnotherKitchen() throws Exception {
        String token = login(email, RAW_PASSWORD);
        Kitchen other = kitchenRepository.saveAndFlush(Kitchen.builder()
                .name("Outra")
                .code("K-" + UUID.randomUUID().toString().substring(0, 8))
                .build());

        mockMvc.perform(put("/api/users/" + user.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kitchenId\":" + other.getId() + "}"))
                .andExpect(status().isForbidden());
    }

    // RBAC: inventário é do supervisor e do estoquista (o usuário semeado é estoquista).
    @Test
    void estoquistaCanOpenInventoryInOwnKitchen() throws Exception {
        Kitchen kitchen = assignNewKitchen(user);
        String token = login(email, RAW_PASSWORD);

        mockMvc.perform(post("/api/inventories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kitchenId\":" + kitchen.getId() + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.responsible.id").value(user.getId().toString()));
    }

    @Test
    void compradorCannotAccessInventory() throws Exception {
        Profile comprador = profileRepository.findByAccessType("comprador")
                .orElseGet(() -> profileRepository.saveAndFlush(Profile.builder().accessType("comprador").build()));
        String compradorEmail = "comprador-" + UUID.randomUUID() + "@inventra.com";
        User compradorUser = userRepository.saveAndFlush(User.builder()
                .id(UUID.randomUUID())
                .name("Comprador de Teste")
                .email(compradorEmail)
                .passwordHash(passwordEncoder.encode(RAW_PASSWORD))
                .profile(comprador)
                .active(true)
                .build());
        Kitchen kitchen = assignNewKitchen(compradorUser);
        String token = login(compradorEmail, RAW_PASSWORD);

        mockMvc.perform(post("/api/inventories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kitchenId\":" + kitchen.getId() + "}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/inventories?kitchenId=" + kitchen.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void tokenStopsWorkingWhenUserIsDeactivated() throws Exception {
        String token = login(email, RAW_PASSWORD);
        user.setActive(false);
        userRepository.saveAndFlush(user);

        mockMvc.perform(get("/api/users/" + user.getId()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginIsCaseInsensitiveOnEmail() throws Exception {
        login(email.toUpperCase(), RAW_PASSWORD);
    }

    @Test
    void malformedJsonReturnsBadRequestInsteadOfServerError() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownRouteReturnsNotFoundInsteadOfServerError() throws Exception {
        String token = login(email, RAW_PASSWORD);

        mockMvc.perform(get("/api/nao-existe").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String token = login(email, RAW_PASSWORD);
        // troca um caractere do meio da assinatura: o último carrega só 4 bits úteis e às vezes não altera a assinatura
        int index = token.length() - 10;
        String tampered = token.substring(0, index) + (token.charAt(index) == 'A' ? 'B' : 'A') + token.substring(index + 1);

        mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    // Auto-cadastro: precisa ser acessível sem token — quem se cadastra ainda não tem conta pra logar.
    @Test
    void registerIsPublicAndCreatesMissingProfile() throws Exception {
        // Sem semear o perfil: o enum AccessType basta, o perfil é criado no primeiro uso.
        assertThat(countProfiles("supervisor")).isZero();

        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newRegisterRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        LoginResponse response = objectMapper.readValue(body, LoginResponse.class);
        assertThat(response.token()).isNotBlank();
        assertThat(response.user().profile().accessType()).isEqualTo("SUPERVISOR");
        assertThat(countProfiles("supervisor")).isEqualTo(1);
    }

    @Test
    void registerReusesExistingProfileInsteadOfDuplicatingIt() throws Exception {
        ensureProfile("supervisor");

        Integer firstProfileId = registerAndGetProfileId();
        Integer secondProfileId = registerAndGetProfileId();

        assertThat(secondProfileId).isEqualTo(firstProfileId);
        assertThat(countProfiles("supervisor")).isEqualTo(1);
    }

    @Test
    void registerRejectsDuplicateEmail() throws Exception {
        String json = objectMapper.writeValueAsString(newRegisterRequest());

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isConflict());
    }

    @Test
    void tokenReturnedByRegisterGrantsAccessToTheNewUserOnlyWhenSent() throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newRegisterRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        LoginResponse registered = objectMapper.readValue(body, LoginResponse.class);
        String userUrl = "/api/users/" + registered.user().id();

        mockMvc.perform(get(userUrl).header(HttpHeaders.AUTHORIZATION, "Bearer " + registered.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(registered.user().email()));

        mockMvc.perform(get(userUrl))
                .andExpect(status().isUnauthorized());
    }

    // Sem esse esquema no OpenAPI o Swagger UI não mostra o botão "Authorize" e não consegue enviar o token.
    @Test
    void openApiDocumentDeclaresBearerSecurityScheme() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
    }

    @Test
    void registerDoesNotAcceptAdminAccessType() throws Exception {
        String json = """
                {"name":"Maria Silva","email":"admin-try-%s@inventra.com","password":"%s","accessType":"ADMIN"}
                """.formatted(UUID.randomUUID(), RAW_PASSWORD);

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());
    }

    private Kitchen assignNewKitchen(User target) {
        Kitchen kitchen = kitchenRepository.saveAndFlush(Kitchen.builder()
                .name("Cozinha de Teste")
                .code("K-" + UUID.randomUUID().toString().substring(0, 8))
                .build());
        target.setKitchen(kitchen);
        userRepository.saveAndFlush(target);
        return kitchen;
    }

    private Integer registerAndGetProfileId() throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newRegisterRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(body, LoginResponse.class).user().profile().id();
    }

    private long countProfiles(String accessType) {
        return profileRepository.findAll().stream()
                .filter(profile -> accessType.equals(profile.getAccessType()))
                .count();
    }

    private void ensureProfile(String accessType) {
        profileRepository.findByAccessType(accessType)
                .orElseGet(() -> profileRepository.saveAndFlush(Profile.builder().accessType(accessType).build()));
    }

    private RegisterRequest newRegisterRequest() {
        return new RegisterRequest("Maria Silva", "register-" + UUID.randomUUID() + "@inventra.com",
                RAW_PASSWORD, AccessType.SUPERVISOR);
    }

    private String login(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readValue(body, LoginResponse.class).token();
    }
}
