package com.inventra.api.infrastructure.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.fasterxml.jackson.databind.JsonNode;
import com.inventra.api.core.domain.profile.Profile;
import com.inventra.api.core.domain.user.User;
import com.inventra.api.infrastructure.repository.ProfileRepository;
import com.inventra.api.infrastructure.repository.UserRepository;

// Contador de cliques da V014 (tb_ui_click_counter + vw_ui_click_ranking) de ponta a ponta.
class UiClickCounterIntegrationTest extends PostgresIntegrationTest {

    private static final String URL = "/api/events/clicks";

    @Autowired private UserRepository userRepository;
    @Autowired private ProfileRepository profileRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void batchIncrementsOnlyTheSentColumnsAndIgnoresUnknownElements() throws Exception {
        long signIn = clicks("bt_wb_login_sign_in");
        long stock = clicks("nav_mb_global_stock");
        long dashboard = clicks("nav_wb_global_dashboard");

        send(post(URL), """
                {"clicks": {"bt_wb_login_sign_in": 2, "nav_mb_global_stock": 5,
                            "bt_wb_does_not_exist": 1, "id_counter": 1}}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordedElements").value(2))
                .andExpect(jsonPath("$.recordedClicks").value(7))
                .andExpect(jsonPath("$.ignored[0]").value("bt_wb_does_not_exist"))
                .andExpect(jsonPath("$.ignored[1]").value("id_counter"));

        assertThat(clicks("bt_wb_login_sign_in")).isEqualTo(signIn + 2);
        assertThat(clicks("nav_mb_global_stock")).isEqualTo(stock + 5);
        assertThat(clicks("nav_wb_global_dashboard")).isEqualTo(dashboard);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_ui_click_counter", Integer.class)).isEqualTo(1);
    }

    @Test
    void invalidBatchIsRejected() throws Exception {
        send(post(URL), "{\"clicks\": {}}").andExpect(status().isBadRequest());
        send(post(URL), "{\"clicks\": {\"bt_wb_login_sign_in\": 0}}").andExpect(status().isBadRequest());
        send(post(URL), "{\"clicks\": {\"bt_wb_login_sign_in\": 10001}}").andExpect(status().isBadRequest());
    }

    @Test
    void batchRequiresAuthentication() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clicks\": {\"bt_wb_login_sign_in\": 1}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rankingReadsTheViewFilteredByPlatform() throws Exception {
        send(post(URL), "{\"clicks\": {\"nav_mb_global_stock\": 3}}").andExpect(status().isOk());
        long stock = clicks("nav_mb_global_stock");
        int mobileColumns = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_name = 'tb_ui_click_counter' AND column_name LIKE '%\\_mb\\_%'", Integer.class);

        JsonNode ranking = json(send(get(URL + "/ranking").param("platform", "MOBILE"), null)
                .andExpect(status().isOk()));

        assertThat(ranking).hasSize(mobileColumns);
        assertThat(ranking).allSatisfy(row -> assertThat(row.get("platform").asText()).isEqualTo("MOBILE"));
        JsonNode stockRow = null;
        for (JsonNode row : ranking) {
            if (row.get("element").asText().equals("nav_mb_global_stock")) {
                stockRow = row;
            }
        }
        assertThat(stockRow).isNotNull();
        assertThat(stockRow.get("clicks").asLong()).isEqualTo(stock);
        assertThat(stockRow.get("description").asText()).isNotBlank();

        send(get(URL + "/ranking").param("platform", "DESKTOP"), null).andExpect(status().isBadRequest());
    }

    @Test
    void rankingIsOnlyForSupervisors() throws Exception {
        Profile estoquista = profileRepository.findByAccessType("estoquista")
                .orElseGet(() -> profileRepository.saveAndFlush(Profile.builder().accessType("estoquista").build()));
        User user = userRepository.saveAndFlush(User.builder()
                .id(UUID.randomUUID()).name("Estoquista")
                .email("est-" + UUID.randomUUID() + "@inventra.com")
                .passwordHash(passwordEncoder.encode("SenhaForte123"))
                .profile(estoquista).kitchen(kitchen).active(true).build());
        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"SenhaForte123\"}".formatted(user.getEmail())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String bearer = "Bearer " + objectMapper.readTree(body).get("token").asText();

        // qualquer papel envia cliques; só o supervisor vê o ranking
        mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clicks\": {\"bt_wb_login_sign_in\": 1}}"))
                .andExpect(status().isOk());
        mockMvc.perform(get(URL + "/ranking").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isForbidden());
    }

    private long clicks(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM tb_ui_click_counter WHERE id_counter = 1", Long.class);
    }
}
