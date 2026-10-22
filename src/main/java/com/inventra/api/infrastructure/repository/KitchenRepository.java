package com.inventra.api.infrastructure.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.inventra.api.core.domain.kitchen.Kitchen;

import jakarta.persistence.LockModeType;

public interface KitchenRepository extends JpaRepository<Kitchen, Integer> {

    Optional<Kitchen> findByCodeIgnoreCase(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select k from Kitchen k where k.id = :id")
    Optional<Kitchen> lockForRanking(@Param("id") Integer id);

    boolean existsByCodeIgnoreCase(String code);

    // Serializa operações "verifica e cria" por cozinha (ex.: só um inventário OPEN por cozinha).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT k FROM Kitchen k WHERE k.id = :id")
    Optional<Kitchen> findByIdForUpdate(@Param("id") Integer id);

    // Descoberta de cozinhas: só as ativas que aceitam pedidos. Linhas [id, name, city, state, latitude,
    // longitude]. O nome nunca é nulo (vazio = sem filtro): o Postgres tipa parâmetro nulo como bytea e
    // quebra o LOWER.
    @Query(value = """
            SELECT k.id_kitchen, k.name, k.city, k.state, k.latitude, k.longitude
            FROM tb_kitchen k
            WHERE k.active = true AND k.accepting_requests = true
              AND LOWER(k.name) LIKE LOWER(CONCAT('%', :name, '%'))
            ORDER BY k.name, k.id_kitchen
            """,
            countQuery = """
            SELECT COUNT(*) FROM tb_kitchen k
            WHERE k.active = true AND k.accepting_requests = true
              AND LOWER(k.name) LIKE LOWER(CONCAT('%', :name, '%'))
            """,
            nativeQuery = true)
    Page<Object[]> discover(@Param("name") String name, Pageable pageable);

    // Mesma lista, por distância do usuário: linhas [id, name, city, state, latitude, longitude, distanceKm].
    // A "caixa" (min/max) descarta pelo índice de latitude/longitude antes de calcular a distância exata
    // (Haversine, raio da Terra de 6371 km). Cozinha sem coordenadas não tem distância: vem no fim da lista.
    @Query(value = """
            SELECT d.id_kitchen, d.name, d.city, d.state, d.latitude, d.longitude, d.distance_km
            FROM (
                SELECT k.id_kitchen, k.name, k.city, k.state, k.latitude, k.longitude,
                       CASE WHEN k.latitude IS NULL OR k.longitude IS NULL THEN NULL
                            ELSE 6371 * ACOS(LEAST(1.0, GREATEST(-1.0,
                                     COS(RADIANS(:lat)) * COS(RADIANS(k.latitude))
                                       * COS(RADIANS(k.longitude) - RADIANS(:lng))
                                     + SIN(RADIANS(:lat)) * SIN(RADIANS(k.latitude)))))
                       END AS distance_km
                FROM tb_kitchen k
                WHERE k.active = true AND k.accepting_requests = true
                  AND LOWER(k.name) LIKE LOWER(CONCAT('%', :name, '%'))
                  AND (k.latitude IS NULL OR k.longitude IS NULL
                       OR (k.latitude BETWEEN :minLat AND :maxLat AND k.longitude BETWEEN :minLng AND :maxLng))
            ) d
            WHERE d.distance_km IS NULL OR d.distance_km <= :radiusKm
            ORDER BY d.distance_km NULLS LAST, d.name, d.id_kitchen
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM (
                SELECT CASE WHEN k.latitude IS NULL OR k.longitude IS NULL THEN NULL
                            ELSE 6371 * ACOS(LEAST(1.0, GREATEST(-1.0,
                                     COS(RADIANS(:lat)) * COS(RADIANS(k.latitude))
                                       * COS(RADIANS(k.longitude) - RADIANS(:lng))
                                     + SIN(RADIANS(:lat)) * SIN(RADIANS(k.latitude)))))
                       END AS distance_km
                FROM tb_kitchen k
                WHERE k.active = true AND k.accepting_requests = true
                  AND LOWER(k.name) LIKE LOWER(CONCAT('%', :name, '%'))
                  AND (k.latitude IS NULL OR k.longitude IS NULL
                       OR (k.latitude BETWEEN :minLat AND :maxLat AND k.longitude BETWEEN :minLng AND :maxLng))
            ) d
            WHERE d.distance_km IS NULL OR d.distance_km <= :radiusKm
            """,
            nativeQuery = true)
    Page<Object[]> discoverNear(@Param("name") String name,
                                @Param("lat") double lat, @Param("lng") double lng,
                                @Param("minLat") double minLat, @Param("maxLat") double maxLat,
                                @Param("minLng") double minLng, @Param("maxLng") double maxLng,
                                @Param("radiusKm") double radiusKm,
                                Pageable pageable);
}
