package com.inventra.api.core.service.alert;

import com.inventra.api.core.domain.alert.Alert;

// Um alerta visto por quem está chamando: "read" é a leitura dessa pessoa, não a do alerta inteiro.
public record AlertView(Alert alert, boolean read) {
}
