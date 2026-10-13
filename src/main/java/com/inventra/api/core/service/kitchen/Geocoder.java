package com.inventra.api.core.service.kitchen;

import java.util.Optional;

// Porta de geocodificação. Em produção é o Nominatim (OpenStreetMap), gratuito e sem chave.
public interface Geocoder {

    // endereço em texto livre -> coordenadas (e cidade/UF, se o serviço souber)
    Optional<GeocodedPlace> search(String address);

    // coordenadas -> cidade/UF
    Optional<GeocodedPlace> reverse(double latitude, double longitude);
}
