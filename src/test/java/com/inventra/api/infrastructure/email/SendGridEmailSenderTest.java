package com.inventra.api.infrastructure.email;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.inventra.api.core.service.email.EmailMessage;

class SendGridEmailSenderTest {

    private MockRestServiceServer server;
    private SendGridEmailSender sender;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        sender = new SendGridEmailSender(builder, "SG.chave-de-teste", "nao-responda@inventra.com", "Inventra");
    }

    @Test
    void sendsTheMessageThroughTheV3ApiWithoutClickTracking() {
        server.expect(requestTo(SendGridEmailSender.BASE_URL + "/v3/mail/send"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer SG.chave-de-teste"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.personalizations[0].to[0].email").value("maria@inventra.com"))
                .andExpect(jsonPath("$.from.email").value("nao-responda@inventra.com"))
                .andExpect(jsonPath("$.from.name").value("Inventra"))
                .andExpect(jsonPath("$.subject").value("Assunto"))
                .andExpect(jsonPath("$.content[0].type").value("text/plain"))
                .andExpect(jsonPath("$.content[0].value").value("texto"))
                .andExpect(jsonPath("$.content[1].type").value("text/html"))
                .andExpect(jsonPath("$.content[1].value").value("<p>html</p>"))
                .andExpect(jsonPath("$.tracking_settings.click_tracking.enable").value(false))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        sender.send(new EmailMessage("maria@inventra.com", "Assunto", "texto", "<p>html</p>"));

        server.verify();
    }

    @Test
    void failsWhenSendGridRejectsTheMessage() {
        server.expect(requestTo(SendGridEmailSender.BASE_URL + "/v3/mail/send"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> sender.send(new EmailMessage("maria@inventra.com", "Assunto", "texto", "<p>html</p>")))
                .isInstanceOf(RestClientException.class);
    }
}
