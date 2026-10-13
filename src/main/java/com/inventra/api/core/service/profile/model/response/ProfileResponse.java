package com.inventra.api.core.service.profile.model.response;

import com.inventra.api.core.domain.profile.Profile;
import com.inventra.api.core.domain.profile.AccessType;

public record ProfileResponse(
        Integer id,
        String accessType,
        String description
) {
    public static ProfileResponse fromEntity(Profile profile) {
        return new ProfileResponse(
                profile.getId(),
                AccessType.display(profile.getAccessType()),
                profile.getDescription()
        );
    }
}
