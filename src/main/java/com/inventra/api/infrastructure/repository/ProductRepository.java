package com.inventra.api.infrastructure.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.inventra.api.core.domain.product.Product;

public interface ProductRepository extends JpaRepository<Product, Integer> {

    boolean existsByBarcode(String barcode);

    Optional<Product> findByBarcode(String barcode);
    Optional<Product> findByRegistrationEventId(UUID registrationEventId);

    boolean existsByCategoryId(Integer categoryId);

    boolean existsByUnitId(Integer unitId);

    @Query("""
            SELECT p FROM Product p
            WHERE (:name IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :name, '%')))
              AND (:categoryId IS NULL OR p.category.id = :categoryId)
              AND (:active IS NULL OR p.active = :active)
            """)
    Page<Product> search(
            @Param("name") String name,
            @Param("categoryId") Integer categoryId,
            @Param("active") Boolean active,
            Pageable pageable);

}
