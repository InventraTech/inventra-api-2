package com.inventra.api.core.service.kitchen;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inventra.api.core.service.kitchen.model.request.CreateKitchenRequest;
import com.inventra.api.core.service.kitchen.model.request.UpdateKitchenRequest;
import com.inventra.api.core.service.kitchen.model.response.KitchenDiscoveryPage;
import com.inventra.api.core.service.kitchen.model.response.KitchenDiscoveryResponse;
import com.inventra.api.core.service.kitchenaccess.KitchenAccessRequest;
import com.inventra.api.core.service.kitchenaccess.KitchenAccessRequestStatus;
import com.inventra.api.core.service.kitchenaccess.KitchenAccessRequestStore;
import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.user.User;
import com.inventra.api.infrastructure.exception.BusinessRuleException;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.KitchenRepository;
import com.inventra.api.infrastructure.repository.UserRepository;
import com.inventra.api.infrastructure.security.KitchenAccessGuard;
import com.inventra.api.infrastructure.security.KitchenCodeLookupLimiter;
import com.inventra.api.infrastructure.security.KitchenDiscoveryLimiter;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class KitchenService implements KitchenUseCase {

    private static final int MAX_CODE_ATTEMPTS = 5;
    private static final int MAX_DISCOVERY_PAGE_SIZE = 50;
    private static final double DEFAULT_DISCOVERY_RADIUS_KM = 50;
    private static final double KM_PER_DEGREE = 111.0;

    private final KitchenRepository repository;
    private final UserRepository userRepository;
    private final KitchenAccessGuard accessGuard;
    private final KitchenCodeGenerator codeGenerator;
    private final KitchenCodeLookupLimiter lookupLimiter;
    private final KitchenDiscoveryLimiter discoveryLimiter;
    private final KitchenAccessRequestStore accessRequestStore;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public Kitchen create(CreateKitchenRequest request) {
        // Cada usuário tem uma cozinha só: quem cria a cozinha é vinculado a ela. Sem isso, a cozinha
        // nasceria inacessível, já que ninguém pode vincular usuário a uma cozinha que não é a sua.
        if (accessGuard.currentKitchenId() != null) {
            throw new BusinessRuleException("Você já está vinculado a uma cozinha.");
        }

        Kitchen kitchen = Kitchen.builder()
            .name(request.name())
            .code(generateUniqueCode())
            .address(request.address())
            .city(blankToNull(request.city()))
            .state(normalizeState(request.state()))
            .latitude(request.latitude())
            .longitude(request.longitude())
            .active(true)
            .build();
        assertCoordinatesTogether(kitchen);
        Kitchen saved = repository.save(kitchen);
        requestLocationIfIncomplete(saved);

        User creator = userRepository.findById(accessGuard.currentUser().getId())
            .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado."));
        creator.setKitchen(saved);
        userRepository.save(creator);

        return saved;
    }

    // O código é a chave que liga usuários à cozinha: é gerado aqui, nunca vem do cliente.
    private String generateUniqueCode() {
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = codeGenerator.generate();
            if (!repository.existsByCodeIgnoreCase(code)) {
                return code;
            }
        }
        throw new BusinessRuleException("Não foi possível gerar o código da cozinha. Tente novamente.");
    }

    @Override
    public Kitchen findById(Integer id) {
        Kitchen kitchen = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Cozinha não encontrada."));
        accessGuard.assertAccess(kitchen.getId());
        return kitchen;
    }

    @Override
    public Kitchen findByCode(String code) {
        // aberta a qualquer usuário autenticado (quem ainda não tem cozinha precisa dela para pedir entrada);
        // por isso limita consultas por usuário e não revela cozinha desativada
        lookupLimiter.register(accessGuard.currentUser().getId());
        return repository.findByCodeIgnoreCase(code.trim())
            .filter(kitchen -> Boolean.TRUE.equals(kitchen.getActive()))
            .orElseThrow(() -> new ResourceNotFoundException("Cozinha não encontrada."));
    }

    @Override
    public List<Kitchen> listActive() {
        // cada usuário só enxerga a própria cozinha: busca só ela, em vez de carregar todas e filtrar
        Integer kitchenId = accessGuard.currentKitchenId();
        if (kitchenId == null) {
            return List.of();
        }
        return repository.findById(kitchenId)
            .filter(kitchen -> Boolean.TRUE.equals(kitchen.getActive()))
            .map(List::of)
            .orElse(List.of());
    }

    @Override
    public Kitchen update(Integer id, UpdateKitchenRequest request) {
        accessGuard.assertAccess(id);
        Kitchen kitchen = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Cozinha não encontrada."));

        if (request.name() != null) {
            kitchen.setName(request.name());
        }
        boolean addressChanged = request.address() != null && !request.address().equals(kitchen.getAddress());
        if (request.address() != null) {
            kitchen.setAddress(request.address());
        }
        if (request.city() != null) {
            kitchen.setCity(blankToNull(request.city()));
        }
        if (request.state() != null) {
            kitchen.setState(normalizeState(request.state()));
        }
        if (request.latitude() != null || request.longitude() != null) {
            kitchen.setLatitude(request.latitude());
            kitchen.setLongitude(request.longitude());
        } else if (addressChanged) {
            // endereço novo sem coordenadas novas: o pin antigo apontaria para o lugar errado
            kitchen.setLatitude(null);
            kitchen.setLongitude(null);
        }
        if (request.acceptingRequests() != null) {
            kitchen.setAcceptingRequests(request.acceptingRequests());
        }
        assertCoordinatesTogether(kitchen);

        Kitchen saved = repository.save(kitchen);
        requestLocationIfIncomplete(saved);
        return saved;
    }

    @Override
    public KitchenDiscoveryPage discover(String name, Double lat, Double lng, Double radiusKm, int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("O parâmetro page não pode ser negativo.");
        }
        if (size < 1 || size > MAX_DISCOVERY_PAGE_SIZE) {
            throw new IllegalArgumentException("O parâmetro size deve ficar entre 1 e " + MAX_DISCOVERY_PAGE_SIZE + ".");
        }
        if ((lat == null) != (lng == null)) {
            throw new IllegalArgumentException("Informe lat e lng juntos.");
        }
        if (lat != null && (Math.abs(lat) > 90 || Math.abs(lng) > 180)) {
            throw new IllegalArgumentException("lat deve ficar entre -90 e 90, e lng entre -180 e 180.");
        }
        if (radiusKm != null && radiusKm <= 0) {
            throw new IllegalArgumentException("O parâmetro radiusKm deve ser maior que zero.");
        }
        UUID userId = accessGuard.currentUser().getId();
        discoveryLimiter.register(userId);

        String filter = name == null ? "" : name.trim();
        PageRequest pageable = PageRequest.of(page, size);
        Page<Object[]> rows;
        if (lat == null) {
            rows = repository.discover(filter, pageable);
        } else {
            double radius = radiusKm == null ? DEFAULT_DISCOVERY_RADIUS_KM : radiusKm;
            double latDelta = radius / KM_PER_DEGREE;
            // perto dos polos (ou com raio enorme) a caixa em longitude dá a volta: aí não filtra por ela
            double cos = Math.cos(Math.toRadians(lat));
            double lngDelta = cos < 0.01 ? 360 : Math.min(360, radius / (KM_PER_DEGREE * cos));
            boolean wraps = lng - lngDelta < -180 || lng + lngDelta > 180;
            rows = repository.discoverNear(filter, lat, lng,
                lat - latDelta, lat + latDelta,
                wraps ? -180 : lng - lngDelta, wraps ? 180 : lng + lngDelta,
                radius, pageable);
        }

        Map<Integer, KitchenAccessRequest> myRequests = accessRequestStore.findLatestByUserPerKitchen(userId);
        List<KitchenDiscoveryResponse> content = rows.getContent().stream()
            .map(row -> {
                Integer id = ((Number) row[0]).intValue();
                return new KitchenDiscoveryResponse(
                    id,
                    (String) row[1],
                    (String) row[2],
                    row[3] == null ? null : row[3].toString(),
                    roundCoordinate(row[4]),
                    roundCoordinate(row[5]),
                    row.length > 6 && row[6] != null ? round(((Number) row[6]).doubleValue(), 1) : null,
                    visibleStatus(myRequests.get(id)));
            })
            .toList();
        return new KitchenDiscoveryPage(content, page, size, rows.getTotalElements());
    }

    // Aprovado não aparece: quem foi aprovado já está na cozinha e não vê mais essa tela.
    private static KitchenAccessRequestStatus visibleStatus(KitchenAccessRequest request) {
        if (request == null || request.status() == KitchenAccessRequestStatus.APPROVED) {
            return null;
        }
        return request.status();
    }

    // 4 casas decimais (~11 m): suficiente para o pin e sem expor a posição exata do estabelecimento.
    private static Double roundCoordinate(Object value) {
        return value == null ? null : round(((Number) value).doubleValue(), 4);
    }

    private static double round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeState(String state) {
        return state == null || state.isBlank() ? null : state.trim().toUpperCase(Locale.ROOT);
    }

    private static void assertCoordinatesTogether(Kitchen kitchen) {
        if ((kitchen.getLatitude() == null) != (kitchen.getLongitude() == null)) {
            throw new IllegalArgumentException("Informe latitude e longitude juntas.");
        }
    }

    // Sem coordenadas (mas com endereço) ou sem cidade/UF: o KitchenGeocodingService completa depois do commit.
    private void requestLocationIfIncomplete(Kitchen kitchen) {
        boolean hasCoordinates = kitchen.getLatitude() != null;
        boolean hasAddress = kitchen.getAddress() != null && !kitchen.getAddress().isBlank();
        boolean missingCityOrState = kitchen.getCity() == null || kitchen.getState() == null;
        if ((!hasCoordinates && hasAddress) || (hasCoordinates && missingCityOrState)) {
            eventPublisher.publishEvent(new KitchenLocationPendingEvent(kitchen.getId()));
        }
    }

    @Override
    public void activate(Integer id) {
        accessGuard.assertAccess(id);
        Kitchen kitchen = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Cozinha não encontrada."));
        kitchen.setActive(true);
        repository.save(kitchen);
    }

    @Override
    public void deactivate(Integer id) {
        accessGuard.assertAccess(id);
        Kitchen kitchen = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Cozinha não encontrada."));
        kitchen.setActive(false);
        repository.save(kitchen);
    }
}
