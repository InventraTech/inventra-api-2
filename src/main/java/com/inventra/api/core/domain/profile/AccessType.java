package com.inventra.api.core.domain.profile;

// Tipos de acesso permitidos para auto-cadastro (/api/auth/register).
public enum AccessType {

    SUPERVISOR,
    ESTOQUISTA,
    COMPRADOR;

    public String toProfileCode() {
        return name().toLowerCase();
    }
}
