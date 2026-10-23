package com.inventra.api.core.service.email;

// Porta de envio de e-mail. Em produção é o SendGrid (API HTTP, porque o Render bloqueia as portas SMTP);
// sem SENDGRID_API_KEY, a mensagem só vai para o log.
public interface EmailSender {

    void send(EmailMessage message);
}
