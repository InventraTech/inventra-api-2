package com.inventra.api.infrastructure.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventra.api.core.domain.alert.AlertType;
import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.profile.Profile;
import com.inventra.api.core.domain.requisition.Requisition;
import com.inventra.api.core.domain.requisition.enums.RequisitionStatus;
import com.inventra.api.core.domain.requisition.enums.RequisitionType;
import com.inventra.api.core.domain.user.User;
import com.inventra.api.infrastructure.repository.AlertRepository;
import com.inventra.api.infrastructure.repository.KitchenRepository;
import com.inventra.api.infrastructure.repository.ProfileRepository;
import com.inventra.api.infrastructure.repository.RequisitionRepository;
import com.inventra.api.infrastructure.repository.UserRepository;

import jakarta.persistence.EntityManager;

// Sair da cozinha e remover membro (INV2-207): desfaz só o vínculo, sem desativar a conta.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class KitchenMembershipIntegrationTest {

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
    private RequisitionRepository requisitionRepository;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ===== POST /api/users/me/leave-kitchen =====

    @Test
    void memberLeavesKeepsAccountAndLosesAccess() throws Exception {
        Kitchen kitchen = newKitchen(true);
        newUser("supervisor", kitchen);
        User member = newUser("estoquista", kitchen);
        String token = login(member.getEmail());

        mockMvc.perform(post("/api/users/me/leave-kitchen").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(member.getId().toString()))
                .andExpect(jsonPath("$.kitchen").doesNotExist())
                .andExpect(jsonPath("$.active").value(true));

        User reloaded = reload(member);
        assertThat(reloaded.getKitchen()).isNull();
        assertThat(reloaded.getActive()).isTrue();

        // o mesmo token já não acessa a cozinha antiga
        mockMvc.perform(get("/api/kitchens/" + kitchen.getId() + "/stock").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void leavingCancelsOnlyTheUsersDraftsInThatKitchen() throws Exception {
        Kitchen kitchen = newKitchen(true);
        newUser("supervisor", kitchen);
        User member = newUser("comprador", kitchen);
        User colleague = newUser("comprador", kitchen);
        Requisition draft = newRequisition(kitchen, member, RequisitionStatus.DRAFT);
        Requisition submitted = newRequisition(kitchen, member, RequisitionStatus.UNDER_REVIEW);
        Requisition colleagueDraft = newRequisition(kitchen, colleague, RequisitionStatus.DRAFT);

        mockMvc.perform(post("/api/users/me/leave-kitchen")
                        .header(HttpHeaders.AUTHORIZATION, bearer(login(member.getEmail()))))
                .andExpect(status().isOk());

        Requisition cancelled = reload(draft);
        assertThat(cancelled.getStatus()).isEqualTo(RequisitionStatus.CANCELLED);
        assertThat(cancelled.getReason()).isEqualTo("Usuário saiu da cozinha.");
        assertThat(reload(submitted).getStatus()).isEqualTo(RequisitionStatus.UNDER_REVIEW);
        assertThat(reload(colleagueDraft).getStatus()).isEqualTo(RequisitionStatus.DRAFT);
    }

    @Test
    void leavingNotifiesTheSupervisors() throws Exception {
        Kitchen kitchen = newKitchen(true);
        newUser("supervisor", kitchen);
        User member = newUser("estoquista", kitchen);

        mockMvc.perform(post("/api/users/me/leave-kitchen")
                        .header(HttpHeaders.AUTHORIZATION, bearer(login(member.getEmail()))))
                .andExpect(status().isOk());

        assertThat(alertRepository.findAll())
                .anySatisfy(alert -> {
                    assertThat(alert.getType()).isEqualTo(AlertType.MEMBER_LEFT);
                    assertThat(alert.getKitchen().getId()).isEqualTo(kitchen.getId());
                    assertThat(alert.getTargetProfile().getAccessType()).isEqualTo("supervisor");
                    assertThat(alert.getMessage()).isEqualTo(member.getName() + " saiu da cozinha.");
                });
    }

    @Test
    void userWithoutKitchenCannotLeave() throws Exception {
        User user = newUser("estoquista", null);

        mockMvc.perform(post("/api/users/me/leave-kitchen")
                        .header(HttpHeaders.AUTHORIZATION, bearer(login(user.getEmail()))))
                .andExpect(status().isConflict());
    }

    @Test
    void lastSupervisorOfAnActiveKitchenCannotLeave() throws Exception {
        Kitchen kitchen = newKitchen(true);
        User supervisor = newUser("supervisor", kitchen);
        newUser("estoquista", kitchen);

        mockMvc.perform(post("/api/users/me/leave-kitchen")
                        .header(HttpHeaders.AUTHORIZATION, bearer(login(supervisor.getEmail()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("Promova outro membro a supervisor ou desative a cozinha antes de sair."));

        assertThat(reload(supervisor).getKitchen()).isNotNull();
    }

    @Test
    void supervisorLeavesWhenThereIsAnotherSupervisor() throws Exception {
        Kitchen kitchen = newKitchen(true);
        User supervisor = newUser("supervisor", kitchen);
        newUser("supervisor", kitchen);

        mockMvc.perform(post("/api/users/me/leave-kitchen")
                        .header(HttpHeaders.AUTHORIZATION, bearer(login(supervisor.getEmail()))))
                .andExpect(status().isOk());

        assertThat(reload(supervisor).getKitchen()).isNull();
    }

    @Test
    void lastSupervisorLeavesADeactivatedKitchen() throws Exception {
        Kitchen kitchen = newKitchen(false);
        User supervisor = newUser("supervisor", kitchen);

        mockMvc.perform(post("/api/users/me/leave-kitchen")
                        .header(HttpHeaders.AUTHORIZATION, bearer(login(supervisor.getEmail()))))
                .andExpect(status().isOk());

        assertThat(reload(supervisor).getKitchen()).isNull();
    }

    @Test
    void userWhoLeftCanRequestEntryInAnotherKitchen() throws Exception {
        Kitchen kitchen = newKitchen(true);
        newUser("supervisor", kitchen);
        Kitchen other = newKitchen(true);
        User member = newUser("estoquista", kitchen);
        String token = login(member.getEmail());

        mockMvc.perform(post("/api/users/me/leave-kitchen").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/kitchen-access-requests")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + other.getCode() + "\"}"))
                .andExpect(status().isCreated());
    }

    // ===== DELETE /api/kitchens/{kitchenId}/members/{userId} =====

    @Test
    void supervisorRemovesAMemberWithoutDeactivatingTheAccount() throws Exception {
        Kitchen kitchen = newKitchen(true);
        User supervisor = newUser("supervisor", kitchen);
        User member = newUser("comprador", kitchen);
        Requisition draft = newRequisition(kitchen, member, RequisitionStatus.DRAFT);

        mockMvc.perform(delete("/api/kitchens/" + kitchen.getId() + "/members/" + member.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(login(supervisor.getEmail()))))
                .andExpect(status().isNoContent());

        User reloaded = reload(member);
        assertThat(reloaded.getKitchen()).isNull();
        assertThat(reloaded.getActive()).isTrue();
        Requisition cancelled = reload(draft);
        assertThat(cancelled.getStatus()).isEqualTo(RequisitionStatus.CANCELLED);
        assertThat(cancelled.getReason()).isEqualTo("Usuário removido da cozinha.");
    }

    @Test
    void supervisorCanRemoveAnotherSupervisor() throws Exception {
        Kitchen kitchen = newKitchen(true);
        User supervisor = newUser("supervisor", kitchen);
        User otherSupervisor = newUser("supervisor", kitchen);

        mockMvc.perform(delete("/api/kitchens/" + kitchen.getId() + "/members/" + otherSupervisor.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(login(supervisor.getEmail()))))
                .andExpect(status().isNoContent());

        assertThat(reload(otherSupervisor).getKitchen()).isNull();
    }

    @Test
    void nonSupervisorCannotRemoveMembers() throws Exception {
        Kitchen kitchen = newKitchen(true);
        User member = newUser("estoquista", kitchen);
        User colleague = newUser("comprador", kitchen);

        mockMvc.perform(delete("/api/kitchens/" + kitchen.getId() + "/members/" + colleague.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(login(member.getEmail()))))
                .andExpect(status().isForbidden());

        assertThat(reload(colleague).getKitchen()).isNotNull();
    }

    @Test
    void supervisorOfAnotherKitchenCannotRemoveMembers() throws Exception {
        Kitchen kitchen = newKitchen(true);
        User member = newUser("estoquista", kitchen);
        User outsider = newUser("supervisor", newKitchen(true));

        mockMvc.perform(delete("/api/kitchens/" + kitchen.getId() + "/members/" + member.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(login(outsider.getEmail()))))
                .andExpect(status().isForbidden());

        assertThat(reload(member).getKitchen()).isNotNull();
    }

    @Test
    void removingSomeoneOutsideTheKitchenIsNotFound() throws Exception {
        Kitchen kitchen = newKitchen(true);
        User supervisor = newUser("supervisor", kitchen);
        User elsewhere = newUser("estoquista", newKitchen(true));
        String token = login(supervisor.getEmail());

        mockMvc.perform(delete("/api/kitchens/" + kitchen.getId() + "/members/" + elsewhere.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/kitchens/" + kitchen.getId() + "/members/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound());
    }

    @Test
    void supervisorCannotRemoveThemselves() throws Exception {
        Kitchen kitchen = newKitchen(true);
        User supervisor = newUser("supervisor", kitchen);
        newUser("supervisor", kitchen);

        mockMvc.perform(delete("/api/kitchens/" + kitchen.getId() + "/members/" + supervisor.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(login(supervisor.getEmail()))))
                .andExpect(status().isConflict());

        assertThat(reload(supervisor).getKitchen()).isNotNull();
    }

    // ===== helpers =====

    private Kitchen newKitchen(boolean active) {
        return kitchenRepository.saveAndFlush(Kitchen.builder()
                .name("Cozinha de Teste")
                .code("K" + UUID.randomUUID().toString().substring(0, 7).toUpperCase())
                .active(active)
                .acceptingRequests(true)
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

    private Requisition newRequisition(Kitchen kitchen, User requester, RequisitionStatus status) {
        return requisitionRepository.saveAndFlush(Requisition.builder()
                .type(RequisitionType.PURCHASE)
                .origin("Fornecedor")
                .status(status)
                .kitchen(kitchen)
                .requester(requester)
                .build());
    }

    // a atualização em lote dos rascunhos não passa pelo contexto de persistência: relê do banco
    private Requisition reload(Requisition requisition) {
        entityManager.flush();
        entityManager.clear();
        return requisitionRepository.findById(requisition.getId()).orElseThrow();
    }

    private User reload(User user) {
        entityManager.flush();
        entityManager.clear();
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private String login(String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
