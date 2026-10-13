package com.inventra.api.infrastructure.client.nominatim;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.inventra.api.core.service.kitchen.GeocodedPlace;
import com.inventra.api.core.service.kitchen.Geocoder;

// Nominatim (geocodificador do OpenStreetMap): gratuito e sem chave. A política de uso exige User-Agent
// identificando o app e no máximo 1 requisição por segundo (quem chama é o KitchenGeocodingService,
// uma de cada vez). lat e lon vêm como texto; a UF vem em address["ISO3166-2-lvl4"] (ex.: BR-SP),
// porque "state" traz o nome por extenso.
@Component
public class NominatimClient implements Geocoder {

    private static final String BASE_URL = "https://nominatim.openstreetmap.org";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient = RestClient.builder()
            .requestFactory(timeoutRequestFactory())
            .build();
    private final String userAgent;

    public NominatimClient(@Value("${app.geocoding.user-agent:Inventra/1.0 (projeto academico Inventra)}") String userAgent) {
        this.userAgent = userAgent;
    }

    @Override
    public Optional<GeocodedPlace> search(String address) {
        Place[] places = restClient.get()
                .uri(BASE_URL + "/search?format=jsonv2&q={q}&countrycodes=br&addressdetails=1&limit=1", address)
                .header(HttpHeaders.USER_AGENT, userAgent)
                .retrieve()
                .body(Place[].class);
        return places == null || places.length == 0 ? Optional.empty() : toPlace(places[0]);
    }

    @Override
    public Optional<GeocodedPlace> reverse(double latitude, double longitude) {
        Place place = restClient.get()
                .uri(BASE_URL + "/reverse?format=jsonv2&lat={lat}&lon={lon}&addressdetails=1", latitude, longitude)
                .header(HttpHeaders.USER_AGENT, userAgent)
                .retrieve()
                .body(Place.class);
        return place == null ? Optional.empty() : toPlace(place);
    }

    private static Optional<GeocodedPlace> toPlace(Place place) {
        if (place.lat() == null || place.lon() == null) {
            return Optional.empty();
        }
        try {
            Map<String, String> address = place.address() == null ? Map.of() : place.address();
            return Optional.of(new GeocodedPlace(
                    Double.parseDouble(place.lat()),
                    Double.parseDouble(place.lon()),
                    city(address),
                    state(address)));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private static String city(Map<String, String> address) {
        for (String key : new String[] {"city", "town", "village", "municipality"}) {
            String value = address.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String state(Map<String, String> address) {
        String iso = address.get("ISO3166-2-lvl4");
        if (iso == null || !iso.matches("BR-[A-Za-z]{2}")) {
            return null;
        }
        return iso.substring(3).toUpperCase(Locale.ROOT);
    }

    private static SimpleClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    private record Place(String lat, String lon, Map<String, String> address) {
    }
}
