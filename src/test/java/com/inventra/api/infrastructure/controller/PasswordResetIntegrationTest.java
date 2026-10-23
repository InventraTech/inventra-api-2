package com.inventra.api.infrastructure.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventra.api.core.domain.profile.Profile;
import com.inventra.api.core.domain.user.User;
import com.inventra.api.core.service.auth.model.request.LoginRequest;
import com.inventra.api.core.service.email.EmailMessage;
import com.inventra.api.core.service.email.EmailSender;
import com.inventra.api.infrastructure.client.openfoodfacts.OpenFoodFactsClient;
import com.inventra.api.infrastructure.redis.InMemoryPasswordResetStore;
import com.inventra.api.infrastructure.repository.ProfileRepository;
import com.inventra.api.infrastructure.repository.UserRepository;

// Fluxo inteiro pelos endpoints públicos (sem token), com segurança, JPA e o e-mail capturado.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PasswordResetIntegrationTest {

    private static final String OLD_PASSWORD = "SenhaForte123";
    private static final String NEW_PASSWORD = "NovaSenha456";
    private static final Pattern CODE = Pattern.compile("Seu código: (\\d{6})");
    private static final Pattern LINK_TOKEN = Pattern.compile("\\?token=([A-Za-z0-9_-]+)");

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ProfileRepository profileRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private InMemoryPasswordResetStore store;

    @MockitoBean private EmailSender emailSender;
    @MockitoBean private OpenFoodFactsClient openFoodFactsClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private User user;

    @BeforeEach
    void setUp() {
        store.clear();
        Profile profile = profileRepository.findByAccessType("estoquista")
                .orElseGet(() -> profileRepository.saveAndFlush(Profile.builder().accessType("estoquista").build()));
        user = userRepository.saveAndFlush(User.builder()
                .id(UUID.randomUUID())
                .name("Maria")
                .email("maria-" + UUID.randomUUID() + "@inventra.com")
                .passwordHash(passwordEncoder.encode(OLD_PASSWORD))
                .profile(profile)
                .active(true)
                .build());
    }

    @Test
    void appFlowWithTheCodeChangesThePassword() throws Exception {
        String text = requestResetEmail();

        String body = mockMvc.perform(post("/api/auth/password/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + user.getEmail() + "\",\"code\":\"" + extract(CODE, text) + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String resetToken = objectMapper.readTree(body).get("resetToken").asText();

        reset(resetToken).andExpect(status().isNoContent());

        login(NEW_PASSWORD).andExpect(status().isOk());
        login(OLD_PASSWORD).andExpect(status().isUnauthorized());
        // o token vale uma vez só
        reset(resetToken).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RESET_TOKEN"));
    }

    @Test
    void webFlowWithTheLinkChangesThePassword() throws Exception {
        String text = requestResetEmail();

        reset(extract(LINK_TOKEN, text)).andExpect(status().isNoContent());

        login(NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void unknownEmailGetsTheSameAnswerAndNoEmail() throws Exception {
        mockMvc.perform(post("/api/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ninguem-" + UUID.randomUUID() + "@inventra.com\"}"))
                .andExpect(status().isAccepted());

        verify(emailSender, after(300).never()).send(any());
    }

    private String requestResetEmail() throws Exception {
        mockMvc.perform(post("/api/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + user.getEmail() + "\"}"))
                .andExpect(status().isAccepted());

        // o envio é assíncrono
        ArgumentCaptor<EmailMessage> message = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender, timeout(5000)).send(message.capture());
        assertThat(message.getValue().to()).isEqualTo(user.getEmail());
        return message.getValue().text();
    }

    private ResultActions reset(String token) throws Exception {
        return mockMvc.perform(post("/api/auth/password/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resetToken\":\"" + token + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"));
    }

    private ResultActions login(String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(user.getEmail(), password))));
    }

    private static String extract(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        assertThat(matcher.find()).as("padrão %s no e-mail", pattern).isTrue();
        return matcher.group(1);
    }
}
