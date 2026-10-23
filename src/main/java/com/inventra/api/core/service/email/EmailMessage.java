package com.inventra.api.core.service.email;

// text é a versão sem formatação, para clientes de e-mail que não mostram HTML.
public record EmailMessage(String to, String subject, String text, String html) {
}
