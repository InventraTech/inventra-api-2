package com.inventra.api.core.service.kitchen;

import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.infrastructure.config.AsyncConfig;
import com.inventra.api.infrastructure.repository.KitchenRepository;

// Plano B da localização: quando a cozinha chega sem coordenadas (ou sem cidade/UF), completa em segundo
// plano, depois de salva, para o POST/PUT não esperar o serviço externo. O resultado fica gravado na
// cozinha: nunca se geocodifica a cada leitura. Falhou ou não encontrou: a cozinha fica sem pin e o
// motivo vai para o log. A fila é em memória: um pedido pendente se perde se a API reiniciar, e a
// cozinha é tentada de novo na próxima edição.
@Service
public class KitchenGeocodingService {

    private static final Logger log = LoggerFactory.getLogger(KitchenGeocodingService.class);

    private final KitchenRepository repository;
    private final Geocoder geocoder;
    private final boolean enabled;
    private final long pauseMs;

    public KitchenGeocodingService(KitchenRepository repository, Geocoder geocoder,
                                   @Value("${app.geocoding.enabled:true}") boolean enabled,
                                   @Value("${app.geocoding.pause-ms:1100}") long pauseMs) {
        this.repository = repository;
        this.geocoder = geocoder;
        this.enabled = enabled;
        this.pauseMs = pauseMs;
    }

    // O executor tem uma thread só e cada chamada espera pauseMs no fim: respeita o limite de
    // 1 requisição por segundo da política de uso do Nominatim.
    @Async(AsyncConfig.GEOCODING_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onLocationPending(KitchenLocationPendingEvent event) {
        if (!enabled) {
            return;
        }
        try {
            if (complete(event.kitchenId())) {
                Thread.sleep(pauseMs);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ex) {
            log.warn("Falha ao geocodificar a cozinha {}: {}", event.kitchenId(), ex.toString());
        }
    }

    // Devolve true se chamou o serviço externo.
    boolean complete(Integer kitchenId) {
        Kitchen kitchen = repository.findById(kitchenId).orElse(null);
        if (kitchen == null) {
            return false;
        }
        boolean hasCoordinates = kitchen.getLatitude() != null && kitchen.getLongitude() != null;
        Optional<GeocodedPlace> place;
        if (!hasCoordinates) {
            if (kitchen.getAddress() == null || kitchen.getAddress().isBlank()) {
                return false;
            }
            String query = Stream.of(kitchen.getAddress(), kitchen.getCity(), kitchen.getState())
                .filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(", "));
            place = geocoder.search(query);
        } else if (kitchen.getCity() == null || kitchen.getState() == null) {
            place = geocoder.reverse(kitchen.getLatitude(), kitchen.getLongitude());
        } else {
            return false;
        }

        if (place.isEmpty()) {
            log.info("Geocodificação sem resultado para a cozinha {}", kitchenId);
            return true;
        }
        // relê antes de gravar: a cozinha pode ter sido editada enquanto o serviço externo respondia
        Kitchen current = repository.findById(kitchenId).orElse(null);
        if (current == null) {
            return true;
        }
        GeocodedPlace found = place.get();
        if (current.getLatitude() == null || current.getLongitude() == null) {
            current.setLatitude(found.latitude());
            current.setLongitude(found.longitude());
        }
        if (current.getCity() == null && found.city() != null) {
            current.setCity(found.city().length() > 120 ? found.city().substring(0, 120) : found.city());
        }
        if (current.getState() == null && found.state() != null) {
            current.setState(found.state());
        }
        repository.save(current);
        return true;
    }
}
