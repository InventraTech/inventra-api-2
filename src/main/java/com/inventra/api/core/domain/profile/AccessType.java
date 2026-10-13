package com.inventra.api.core.domain.profile;

import java.util.Locale;

// Tipos de acesso permitidos para auto-cadastro (/api/auth/register).
public enum AccessType {

    SUPERVISOR,
    ESTOQUISTA,
    COMPRADOR;

    public String toProfileCode() {
        return name().toLowerCase();
    }

    // Formato de saída da API: sempre em maiúsculas, igual ao enum (o banco guarda em minúsculas).
    public static String display(String profileCode) {
        return profileCode == null ? null : profileCode.toUpperCase(Locale.ROOT);
    }
}
