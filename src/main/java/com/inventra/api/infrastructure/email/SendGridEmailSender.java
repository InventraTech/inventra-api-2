package com.inventra.api.infrastructure.email;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.inventra.api.core.service.email.EmailMessage;
import com.inventra.api.core.service.email.EmailSender;

// SendGrid pela API v3 (HTTPS). O remetente precisa estar verificado no painel do SendGrid (Single Sender
// Verification), senão a API recusa. O rastreio de cliques fica desligado: ele reescreveria o link de
// redefinição de senha para passar pelo domínio do SendGrid.
public class SendGridEmailSender implements EmailSender {

    static final String BASE_URL = "https://api.sendgrid.com";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final String fromEmail;
    private final String fromName;

    public SendGridEmailSender(String apiKey, String fromEmail, String fromName) {
        this(RestClient.builder().requestFactory(timeoutRequestFactory()), apiKey, fromEmail, fromName);
    }

    SendGridEmailSender(RestClient.Builder builder, String apiKey, String fromEmail, String fromName) {
        this.restClient = builder
                .baseUrl(BASE_URL)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
        this.fromEmail = fromEmail;
        this.fromName = fromName;
    }

    @Override
    public void send(EmailMessage message) {
        // a API exige text/plain antes de text/html
        Map<String, Object> body = Map.of(
                "personalizations", List.of(Map.of("to", List.of(Map.of("email", message.to())))),
                "from", Map.of("email", fromEmail, "name", fromName),
                "subject", message.subject(),
                "content", List.of(
                        Map.of("type", "text/plain", "value", message.text()),
                        Map.of("type", "text/html", "value", message.html())),
                "tracking_settings", Map.of("click_tracking", Map.of("enable", false, "enable_text", false)));

        restClient.post()
                .uri("/v3/mail/send")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private static SimpleClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }
}
