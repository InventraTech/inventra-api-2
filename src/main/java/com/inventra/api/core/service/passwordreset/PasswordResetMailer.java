package com.inventra.api.core.service.passwordreset;

import java.time.Duration;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import com.inventra.api.core.service.email.EmailMessage;
import com.inventra.api.core.service.email.EmailSender;
import com.inventra.api.infrastructure.config.AsyncConfig;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// Monta e envia o e-mail de redefinição fora da thread da requisição. Uma falha no envio só vai para o
// log: a pessoa não recebe o e-mail e pede de novo.
@Component
@Slf4j
@RequiredArgsConstructor
public class PasswordResetMailer {

    static final String SUBJECT = "Redefinição de senha - Inventra";

    private final EmailSender emailSender;

    @Async(AsyncConfig.EMAIL_EXECUTOR)
    public void send(String to, String name, String code, String link, Duration ttl) {
        try {
            emailSender.send(new EmailMessage(to, SUBJECT, text(name, code, link, ttl), html(name, code, link, ttl)));
        } catch (RuntimeException ex) {
            log.warn("Falha ao enviar o e-mail de redefinição de senha: {}", ex.toString());
        }
    }

    private static String text(String name, String code, String link, Duration ttl) {
        return """
                Olá, %s!

                Recebemos um pedido para redefinir a senha da sua conta no Inventra.

                Seu código: %s

                Digite o código no app, ou abra o link abaixo para criar a nova senha pelo navegador:
                %s

                O código e o link valem por %d minutos e só podem ser usados uma vez.
                Se você não pediu a redefinição, ignore este e-mail: sua senha continua a mesma.
                """.formatted(name, code, link, ttl.toMinutes());
    }

    private static String html(String name, String code, String link, Duration ttl) {
        String safeLink = HtmlUtils.htmlEscape(link);
        return """
                <div style="font-family:Arial,sans-serif;max-width:480px;margin:0 auto;color:#1f2933">
                  <h2 style="margin-bottom:8px">Redefinição de senha</h2>
                  <p>Olá, %s!</p>
                  <p>Recebemos um pedido para redefinir a senha da sua conta no Inventra.</p>
                  <p style="margin:24px 0 8px">Seu código:</p>
                  <p style="font-size:32px;font-weight:bold;letter-spacing:8px;margin:0">%s</p>
                  <p style="margin-top:24px">Digite o código no app, ou crie a nova senha pelo navegador:</p>
                  <p><a href="%s" style="display:inline-block;padding:12px 20px;background:#2563eb;color:#ffffff;
                     text-decoration:none;border-radius:6px">Redefinir senha</a></p>
                  <p style="font-size:13px;color:#52606d">O código e o link valem por %d minutos e só podem ser
                     usados uma vez. Se você não pediu a redefinição, ignore este e-mail: sua senha continua a mesma.</p>
                </div>
                """.formatted(HtmlUtils.htmlEscape(name), code, safeLink, ttl.toMinutes());
    }
}
