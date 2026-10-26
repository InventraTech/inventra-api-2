package com.inventra.api.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.inventra.api.core.service.email.EmailSender;
import com.inventra.api.infrastructure.email.LoggingEmailSender;
import com.inventra.api.infrastructure.email.SendGridEmailSender;

import lombok.extern.slf4j.Slf4j;

// Com SENDGRID_API_KEY, os e-mails saem pelo SendGrid; sem ela, a API sobe normalmente e só registra os
// e-mails no log (o mesmo esquema do Firebase no login com Google).
@Configuration
@Slf4j
public class EmailConfig {

    @Bean
    public EmailSender emailSender(@Value("${app.mail.sendgrid.api-key:}") String apiKey,
                                   @Value("${app.mail.from:}") String fromEmail,
                                   @Value("${app.mail.from-name:Inventra}") String fromName) {
        if (apiKey.isBlank()) {
            log.warn("SENDGRID_API_KEY não configurada: e-mails não serão enviados, só registrados no log");
            return new LoggingEmailSender();
        }
        if (fromEmail.isBlank()) {
            throw new IllegalStateException("MAIL_FROM é obrigatório quando SENDGRID_API_KEY está configurada");
        }
        return new SendGridEmailSender(apiKey, fromEmail, fromName);
    }
}
