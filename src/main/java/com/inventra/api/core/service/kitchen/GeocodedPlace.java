package com.inventra.api.core.service.kitchen;

// Resultado da geocodificação; cidade e UF podem vir nulas quando o serviço não as identifica.
public record GeocodedPlace(
        double latitude,
        double longitude,
        String city,
        String state
) {
}
