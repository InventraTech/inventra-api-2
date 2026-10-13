package com.inventra.api.infrastructure.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.profile.Profile;
import com.inventra.api.core.domain.user.User;
import com.inventra.api.core.service.auth.model.request.LoginRequest;
import com.inventra.api.core.service.auth.model.response.LoginResponse;
import com.inventra.api.infrastructure.client.openfoodfacts.OpenFoodFactsClient;
import com.inventra.api.infrastructure.repository.KitchenRepository;
import com.inventra.api.infrastructure.repository.ProfileRepository;
import com.inventra.api.infrastructure.repository.UserRepository;

// Regras de autorização e de negócio que só aparecem com o contexto inteiro (segurança + serviços + JPA).
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthorizationRulesIntegrationTest {

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
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private OpenFoodFactsClient openFoodFactsClient;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    // ===== Token =====

    @Test
    void changingPasswordInvalidatesTokensIssuedBefore() throws Exception {
        User user = newUser("estoquista", null);
        String token = login(user.getEmail());

        mockMvc.perform(patch("/api/users/" + user.getId() + "/password")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + RAW_PASSWORD + "\",\"newPassword\":\"OutraSenha456\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/" + user.getId()).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginIsBlockedAfterTooManyWrongPasswords() throws Exception {
        User user = newUser("estoquista", null);
        String wrong = objectMapper.writeValueAsString(new LoginRequest(user.getEmail(), "senha-errada"));

        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(wrong))
                    .andExpect(status().isUnauthorized());
        }

        // bloqueado mesmo com a senha certa, até a janela expirar
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(user.getEmail(), RAW_PASSWORD))))
                .andExpect(status().isTooManyRequests());
    }

    // ===== Usuários =====

    @Test
    void supervisorListsOnlyUsersOfOwnKitchen() throws Exception {
        Kitchen kitchen = newKitchen();
        User supervisor = newUser("supervisor", kitchen);
        User colleague = newUser("estoquista", kitchen);
        User unassigned = newUser("comprador", null);

        mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, bearer(login(supervisor.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + colleague.getId() + "')]").exists())
                .andExpect(jsonPath("$[?(@.id == '" + unassigned.getId() + "')]").doesNotExist());
    }

    @Test
    void supervisorCannotDeactivateUserWithoutKitchen() throws Exception {
        User supervisor = newUser("supervisor", newKitchen());
        User unassigned = newUser("comprador", null);

        mockMvc.perform(patch("/api/users/" + unassigned.getId() + "/deactivate")
                        .header(HttpHeaders.AUTHORIZATION, bearer(login(supervisor.getEmail()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void lastSupervisorCannotDemoteThemselves() throws Exception {
        User supervisor = newUser("supervisor", newKitchen());
        Profile estoquista = profile("estoquista");

        mockMvc.perform(put("/api/users/" + supervisor.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(login(supervisor.getEmail())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileId\":" + estoquista.getId() + "}"))
                .andExpect(status().isConflict());
    }

    // ===== Perfis =====

    @Test
    void baseProfilesCannotBeRenamed() throws Exception {
        User supervisor = newUser("supervisor", newKitchen());

        mockMvc.perform(put("/api/profiles/" + supervisor.getProfile().getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(login(supervisor.getEmail())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accessType\":\"qualquer-coisa\"}"))
                .andExpect(status().isConflict());
    }

    // ===== Requisições =====

    @Test
    void compradorCannotCancelSomeoneElsesRequisition() throws Exception {
        Kitchen kitchen = newKitchen();
        User author = newUser("comprador", kitchen);
        User other = newUser("comprador", kitchen);
        Integer requisitionId = createRequisition(login(author.getEmail()), kitchen, "CONSUMPTION");

        mockMvc.perform(patch("/api/requisitions/" + requisitionId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(login(other.getEmail())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"não é minha\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void requisitionWithoutItemsCannotBeApproved() throws Exception {
        Kitchen kitchen = newKitchen();
        User supervisor = newUser("supervisor", kitchen);
        String token = login(supervisor.getEmail());
        Integer requisitionId = createRequisition(token, kitchen, "CONSUMPTION");

        mockMvc.perform(patch("/api/requisitions/" + requisitionId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isConflict());
    }

    // ===== helpers =====

    private Integer createRequisition(String token, Kitchen kitchen, String type) throws Exception {
        String body = mockMvc.perform(post("/api/requisitions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"" + type + "\",\"origin\":\"cozinha\",\"kitchenId\":" + kitchen.getId() + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asInt();
    }

    private Kitchen newKitchen() {
        return kitchenRepository.saveAndFlush(Kitchen.builder()
                .name("Cozinha de Teste")
                .code("K-" + UUID.randomUUID().toString().substring(0, 8))
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

    private String login(String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, RAW_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(body, LoginResponse.class).token();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
