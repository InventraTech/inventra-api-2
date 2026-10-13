package com.inventra.api.infrastructure.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.user.User;

// Regra de negócio: cada usuário (inclusive supervisor) só acessa a cozinha
// do próprio cadastro (user.kitchen) — sem cozinha própria, sem acesso a nada com kitchenId.
@Component
public class KitchenAccessGuard {

    public void assertAccess(Integer kitchenId) {
        if (!hasAccess(kitchenId)) {
            throw new AccessDeniedException("Você não tem acesso a essa cozinha.");
        }
    }

    public boolean hasAccess(Integer kitchenId) {
        if (kitchenId == null) {
            return false;
        }
        Integer ownKitchenId = currentKitchenId();
        return ownKitchenId != null && ownKitchenId.equals(kitchenId);
    }

    public Integer currentKitchenId() {
        Kitchen kitchen = currentUser().getKitchen();
        return kitchen != null ? kitchen.getId() : null;
    }

    public boolean isSupervisor() {
        return Roles.SUPERVISOR_PROFILE.equalsIgnoreCase(currentUser().getProfile().getAccessType());
    }

    public User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            throw new AccessDeniedException("Usuário não autenticado.");
        }
        return principal.getUser();
    }
}
