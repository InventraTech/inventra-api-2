package com.inventra.api.core.service.user.model.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.inventra.api.core.domain.profile.AccessType;
import com.inventra.api.core.domain.user.User;

public record UserResponse(
        UUID id,
        String name,
        String email,
        KitchenSummary kitchen,
        ProfileSummary profile,
        Boolean active,
        // false em conta criada pelo Google que ainda não definiu uma senha
        boolean hasPassword,
        LocalDateTime lastLogin,
        LocalDateTime createdAt
) {
    public record KitchenSummary(Integer id, String name) {
    }

    public record ProfileSummary(Integer id, String accessType) {
    }

    public static UserResponse fromEntity(User user) {
        var kitchen = user.getKitchen() != null
                ? new KitchenSummary(user.getKitchen().getId(), user.getKitchen().getName())
                : null;
        var profile = new ProfileSummary(user.getProfile().getId(), AccessType.display(user.getProfile().getAccessType()));

        return new UserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                kitchen,
                profile,
                user.getActive(),
                user.getPasswordHash() != null,
                user.getLastLogin(),
                user.getCreatedAt()
        );
    }
}
