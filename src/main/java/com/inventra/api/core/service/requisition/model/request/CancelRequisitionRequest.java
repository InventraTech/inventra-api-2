package com.inventra.api.core.service.requisition.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelRequisitionRequest(
        @NotBlank
        @Size(max = 255)
        String reason
) {
}
