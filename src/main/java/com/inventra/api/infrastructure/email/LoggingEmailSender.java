package com.inventra.api.infrastructure.email;

import com.inventra.api.core.service.email.EmailMessage;
import com.inventra.api.core.service.email.EmailSender;

import lombok.extern.slf4j.Slf4j;

// Usado quando SENDGRID_API_KEY está vazia (desenvolvimento local e testes): nada é enviado, o texto do
// e-mail vai para o log, com o código de redefinição. Em produção a chave tem que estar configurada.
@Slf4j
public class LoggingEmailSender implements EmailSender {

    @Override
    public void send(EmailMessage message) {
        log.info("E-mail não enviado (SENDGRID_API_KEY vazia) to={} subject={}\n{}",
                message.to(), message.subject(), message.text());
    }
}
