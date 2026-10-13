package com.inventra.api.core.service.stockbatch.model.request;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record RestockRequest(
        @NotNull
        @Positive
        BigDecimal quantity
) {
}
