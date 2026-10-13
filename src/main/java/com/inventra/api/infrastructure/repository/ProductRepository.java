package com.inventra.api.infrastructure.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.inventra.api.core.domain.product.Product;

public interface ProductRepository extends JpaRepository<Product, Integer> {

    boolean existsByBarcode(String barcode);

    @EntityGraph(attributePaths = {"category", "unit"})
    Optional<Product> findByBarcode(String barcode);

    Optional<Product> findByRegistrationEventId(UUID registrationEventId);

    boolean existsByCategoryId(Integer categoryId);

    boolean existsByUnitId(Integer unitId);

    @EntityGraph(attributePaths = {"category", "unit"})
    @Query("""
            SELECT p FROM Product p
            WHERE LOWER(p.name) LIKE LOWER(CONCAT('%', :name, '%'))
              AND (:categoryId IS NULL OR p.category.id = :categoryId)
              AND (:active IS NULL OR p.active = :active)
            """)
    Page<Product> search(
            @Param("name") String name,
            @Param("categoryId") Integer categoryId,
            @Param("active") Boolean active,
            Pageable pageable);

    // Produtos ativos ligados à cozinha (com lote em qualquer status ou com parâmetro de estoque), já com
    // categoria e unidade, ordenados por nome. Linhas [Product, ProductKitchenParameter ou null]. O nome
    // nunca é nulo (vazio = sem filtro), pelo mesmo motivo do search: o Postgres tipa um parâmetro nulo
    // como bytea e quebra o LOWER.
    @Query("""
            SELECT p, pkp FROM Product p
            JOIN FETCH p.unit
            LEFT JOIN FETCH p.category
            LEFT JOIN ProductKitchenParameter pkp ON pkp.product = p AND pkp.kitchen.id = :kitchenId
            WHERE p.active = true
              AND (pkp.minStock IS NOT NULL
                   OR EXISTS (SELECT 1 FROM StockBatch sb WHERE sb.product = p AND sb.kitchen.id = :kitchenId))
              AND LOWER(p.name) LIKE LOWER(CONCAT('%', :name, '%'))
              AND (:categoryId IS NULL OR p.category.id = :categoryId)
            ORDER BY p.name
            """)
    List<Object[]> findKitchenProducts(
            @Param("kitchenId") Integer kitchenId,
            @Param("name") String name,
            @Param("categoryId") Integer categoryId);

}
