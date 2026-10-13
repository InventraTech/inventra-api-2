package com.inventra.api.infrastructure.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.product.Product;
import com.inventra.api.core.domain.product.ProductKitchenParameter;
import com.inventra.api.core.domain.product.ProductKitchenParameterId;
import com.inventra.api.core.domain.profile.Profile;
import com.inventra.api.core.domain.stock.StockBatch;
import com.inventra.api.core.domain.unit.Unit;
import com.inventra.api.core.domain.user.User;
import com.inventra.api.infrastructure.client.openfoodfacts.OpenFoodFactsClient;
import com.inventra.api.infrastructure.repository.KitchenRepository;
import com.inventra.api.infrastructure.repository.ProductKitchenParameterRepository;
import com.inventra.api.infrastructure.repository.ProductRepository;
import com.inventra.api.infrastructure.repository.ProfileRepository;
import com.inventra.api.infrastructure.repository.StockBatchRepository;
import com.inventra.api.infrastructure.repository.UnitRepository;
import com.inventra.api.infrastructure.repository.UserRepository;

// Pedidos do time mobile: refresh token, /users/me, accessType, busca e geração de código da cozinha,
// pedido de entrada na cozinha e estoque baixo com o produto.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MobileRequestsIntegrationTest {

    private static final String RAW_PASSWORD = "SenhaForte123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private KitchenRepository kitchenRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UnitRepository unitRepository;

    @Autowired
    private ProductKitchenParameterRepository parameterRepository;

    @Autowired
    private StockBatchRepository stockBatchRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private OpenFoodFactsClient openFoodFactsClient;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    // ===== Refresh token =====

    @Test
    void loginReturnsRefreshTokenAndRefreshRotatesIt() throws Exception {
        User user = newUser("estoquista", null);
        JsonNode login = loginBody(user.getEmail());
        String firstRefresh = login.get("refreshToken").asText();

        String body = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + firstRefresh + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(user.getEmail()))
                .andReturn().getResponse().getContentAsString();

        JsonNode refreshed = objectMapper.readTree(body);
        org.assertj.core.api.Assertions.assertThat(refreshed.get("refreshToken").asText()).isNotEqualTo(firstRefresh);

        // o access token novo funciona
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(refreshed.get("token").asText())))
                .andExpect(status().isOk());
    }

    @Test
    void refreshWithUnknownTokenIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"nao-existe\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRequiresTheToken() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        User user = newUser("estoquista", null);
        String refresh = loginBody(user.getEmail()).get("refreshToken").asText();
        String payload = "{\"refreshToken\":\"" + refresh + "\"}";

        mockMvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changingThePasswordBlocksTheRefresh() throws Exception {
        User user = newUser("estoquista", null);
        JsonNode login = loginBody(user.getEmail());

        mockMvc.perform(patch("/api/users/" + user.getId() + "/password")
                        .header(HttpHeaders.AUTHORIZATION, bearer(login.get("token").asText()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + RAW_PASSWORD + "\",\"newPassword\":\"OutraSenha456\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + login.get("refreshToken").asText() + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ===== /users/me e accessType =====

    @Test
    void meReturnsTheLoggedUserWithUppercaseAccessType() throws Exception {
        User user = newUser("supervisor", newKitchen());

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(login(user.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.profile.accessType").value("SUPERVISOR"))
                .andExpect(jsonPath("$.kitchen.id").value(user.getKitchen().getId()));
    }

    @Test
    void loginResponseUsesUppercaseAccessType() throws Exception {
        User user = newUser("estoquista", null);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.profile.accessType").value("ESTOQUISTA"));
    }

    // ===== Cozinha: busca e geração de código =====

    @Test
    void userWithoutKitchenFindsKitchenByCodeWithMinimalData() throws Exception {
        Kitchen kitchen = newKitchen();
        User user = newUser("estoquista", null);

        mockMvc.perform(get("/api/kitchens/by-code/" + kitchen.getCode().toLowerCase())
                        .header(HttpHeaders.AUTHORIZATION, bearer(login(user.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(kitchen.getId()))
                .andExpect(jsonPath("$.name").value(kitchen.getName()))
                .andExpect(jsonPath("$.address").doesNotExist())
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void lookupDoesNotRevealDeactivatedKitchens() throws Exception {
        Kitchen kitchen = newKitchen();
        kitchen.setActive(false);
        kitchenRepository.saveAndFlush(kitchen);
        User user = newUser("estoquista", null);

        mockMvc.perform(get("/api/kitchens/by-code/" + kitchen.getCode())
                        .header(HttpHeaders.AUTHORIZATION, bearer(login(user.getEmail()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void lookupIsRateLimitedPerUser() throws Exception {
        User user = newUser("estoquista", null);
        String token = login(user.getEmail());

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/api/kitchens/by-code/NAOEXISTE").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isNotFound());
        }

        mockMvc.perform(get("/api/kitchens/by-code/NAOEXISTE").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void creatingAKitchenGeneratesTheCodeAndLinksTheSupervisor() throws Exception {
        User supervisor = newUser("supervisor", null);
        String token = login(supervisor.getEmail());

        mockMvc.perform(post("/api/kitchens")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cozinha Central\",\"code\":\"MANUAL\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.matchesPattern("[A-HJ-NP-Z2-9]{6}")))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not("MANUAL")));

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.kitchen.name").value("Cozinha Central"));
    }

    // ===== Pedido de entrada na cozinha =====

    @Test
    void applicantRequestsAndSupervisorApproves() throws Exception {
        Kitchen kitchen = newKitchen();
        User supervisor = newUser("supervisor", kitchen);
        User applicant = newUser("estoquista", null);
        String supervisorToken = login(supervisor.getEmail());
        String applicantToken = login(applicant.getEmail());

        String created = mockMvc.perform(post("/api/kitchen-access-requests")
                        .header(HttpHeaders.AUTHORIZATION, bearer(applicantToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + kitchen.getCode() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.kitchen.id").value(kitchen.getId()))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String requestId = objectMapper.readTree(created).get("id").asText();

        // segundo pedido enquanto o primeiro está pendente
        mockMvc.perform(post("/api/kitchen-access-requests")
                        .header(HttpHeaders.AUTHORIZATION, bearer(applicantToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + kitchen.getCode() + "\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/kitchen-access-requests/mine").header(HttpHeaders.AUTHORIZATION, bearer(applicantToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(get("/api/kitchen-access-requests?status=PENDING")
                        .header(HttpHeaders.AUTHORIZATION, bearer(supervisorToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(requestId))
                .andExpect(jsonPath("$[0].user.email").value(applicant.getEmail()));

        mockMvc.perform(post("/api/kitchen-access-requests/" + requestId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, bearer(supervisorToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value(supervisor.getId().toString()))
                .andExpect(jsonPath("$.expiresAt").doesNotExist());

        // o mesmo token já enxerga a cozinha: o vínculo é lido do banco
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(applicantToken)))
                .andExpect(jsonPath("$.kitchen.id").value(kitchen.getId()));
        mockMvc.perform(get("/api/kitchen-access-requests/mine").header(HttpHeaders.AUTHORIZATION, bearer(applicantToken)))
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // decidido não decide de novo
        mockMvc.perform(post("/api/kitchen-access-requests/" + requestId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, bearer(supervisorToken)))
                .andExpect(status().isConflict());
    }

    @Test
    void supervisorRejectsWithReason() throws Exception {
        Kitchen kitchen = newKitchen();
        User supervisor = newUser("supervisor", kitchen);
        User applicant = newUser("comprador", null);
        String requestId = createRequest(login(applicant.getEmail()), kitchen);

        mockMvc.perform(post("/api/kitchen-access-requests/" + requestId + "/reject")
                        .header(HttpHeaders.AUTHORIZATION, bearer(login(supervisor.getEmail())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Não conheço você\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("Não conheço você"));

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(login(applicant.getEmail()))))
                .andExpect(jsonPath("$.kitchen").doesNotExist());
    }

    @Test
    void userAlreadyInAKitchenCannotRequestEntry() throws Exception {
        Kitchen kitchen = newKitchen();
        User member = newUser("estoquista", newKitchen());

        mockMvc.perform(post("/api/kitchen-access-requests")
                        .header(HttpHeaders.AUTHORIZATION, bearer(login(member.getEmail())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + kitchen.getCode() + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void supervisorOfAnotherKitchenCannotDecide() throws Exception {
        Kitchen kitchen = newKitchen();
        User applicant = newUser("estoquista", null);
        User outsider = newUser("supervisor", newKitchen());
        String requestId = createRequest(login(applicant.getEmail()), kitchen);

        mockMvc.perform(post("/api/kitchen-access-requests/" + requestId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, bearer(login(outsider.getEmail()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlySupervisorsListAndDecide() throws Exception {
        Kitchen kitchen = newKitchen();
        User applicant = newUser("estoquista", null);
        User stockClerk = newUser("estoquista", kitchen);
        String requestId = createRequest(login(applicant.getEmail()), kitchen);
        String clerkToken = login(stockClerk.getEmail());

        mockMvc.perform(get("/api/kitchen-access-requests").header(HttpHeaders.AUTHORIZATION, bearer(clerkToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/kitchen-access-requests/" + requestId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, bearer(clerkToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void supervisorCannotPullAUserWithoutKitchenAnymore() throws Exception {
        Kitchen kitchen = newKitchen();
        User supervisor = newUser("supervisor", kitchen);
        User unassigned = newUser("comprador", null);

        mockMvc.perform(put("/api/users/" + unassigned.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(login(supervisor.getEmail())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kitchenId\":" + kitchen.getId() + "}"))
                .andExpect(status().isForbidden());
    }

    // ===== Estoque baixo =====

    @Test
    void lowStockBringsTheProductAndItsUnit() throws Exception {
        Kitchen kitchen = newKitchen();
        User supervisor = newUser("supervisor", kitchen);
        Unit unit = unitRepository.saveAndFlush(Unit.builder()
                .symbol("u" + UUID.randomUUID().toString().substring(0, 4)).description("Quilo").build());
        Product rice = productRepository.saveAndFlush(Product.builder().name("Arroz").unit(unit).build());
        Product beans = productRepository.saveAndFlush(Product.builder().name("Feijão").unit(unit).build());
        addParameter(rice, kitchen, "10");
        addParameter(beans, kitchen, "5");
        addBatch(rice, kitchen, "2");
        addBatch(beans, kitchen, "20");

        mockMvc.perform(get("/api/stock-batches/low-stock")
                        .header(HttpHeaders.AUTHORIZATION, bearer(login(supervisor.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].productId").value(rice.getId()))
                .andExpect(jsonPath("$[0].product.name").value("Arroz"))
                .andExpect(jsonPath("$[0].product.unit.symbol").value(unit.getSymbol()))
                .andExpect(jsonPath("$[0].currentQuantity").value(2))
                .andExpect(jsonPath("$[0].minStock").value(10));
    }

    // ===== Apoio =====

    private void addParameter(Product product, Kitchen kitchen, String minStock) {
        parameterRepository.saveAndFlush(ProductKitchenParameter.builder()
                .id(new ProductKitchenParameterId(product.getId(), kitchen.getId()))
                .product(product)
                .kitchen(kitchen)
                .minStock(new BigDecimal(minStock))
                .build());
    }

    private void addBatch(Product product, Kitchen kitchen, String quantity) {
        stockBatchRepository.saveAndFlush(StockBatch.builder()
                .product(product)
                .kitchen(kitchen)
                .batchNumber("L-" + UUID.randomUUID().toString().substring(0, 8))
                .initialQuantity(new BigDecimal(quantity))
                .currentQuantity(new BigDecimal(quantity))
                .entryDate(LocalDate.now())
                .expirationDate(LocalDate.now().plusDays(30))
                .build());
    }

    private String createRequest(String token, Kitchen kitchen) throws Exception {
        String body = mockMvc.perform(post("/api/kitchen-access-requests")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + kitchen.getCode() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private Kitchen newKitchen() {
        return kitchenRepository.saveAndFlush(Kitchen.builder()
                .name("Cozinha de Teste")
                .code("K" + UUID.randomUUID().toString().substring(0, 7).toUpperCase())
                .build());
    }

    private Profile profile(String accessType) {
        return profileRepository.findByAccessType(accessType)
                .orElseGet(() -> profileRepository.saveAndFlush(Profile.builder().accessType(accessType).build()));
    }

    private User newUser(String accessType, Kitchen kitchen) {
        return userRepository.saveAndFlush(User.builder()
                .id(UUID.randomUUID())
                .name("Usuário " + accessType)
                .email(accessType + "-" + UUID.randomUUID() + "@inventra.com")
                .passwordHash(passwordEncoder.encode(RAW_PASSWORD))
                .profile(profile(accessType))
                .kitchen(kitchen)
                .active(true)
                .build());
    }

    private JsonNode loginBody(String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private String login(String email) throws Exception {
        return loginBody(email).get("token").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
