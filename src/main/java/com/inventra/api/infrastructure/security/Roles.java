package com.inventra.api.infrastructure.security;

// Papéis do sistema (tb_profile.access_type → authority ROLE_<ACCESS_TYPE>):
// SUPERVISOR faz tudo; ESTOQUISTA só os fluxos de entrada/baixa de estoque; COMPRADOR só requisições.
public final class Roles {

    public static final String SUPERVISOR_PROFILE = "supervisor";
    public static final String COMPRADOR_PROFILE = "comprador";

    public static final String SUPERVISOR = "hasRole('SUPERVISOR')";
    public static final String STOCK = "hasAnyRole('SUPERVISOR', 'ESTOQUISTA')";
    public static final String REQUISITION = "hasAnyRole('SUPERVISOR', 'COMPRADOR')";

    private Roles() {
    }
}
