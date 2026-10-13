package com.inventra.api.infrastructure.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.inventra.api.core.domain.stock.StockMovement;
import com.inventra.api.core.domain.stock.enums.StockMovementType;

public interface StockMovementRepository extends JpaRepository<StockMovement, Integer> {

    // Histórico da cozinha, do mais recente para o mais antigo, com filtros opcionais de produto e tipo.
    @EntityGraph(attributePaths = {"batch", "batch.product", "user"})
    @Query("""
            SELECT m FROM StockMovement m
            WHERE m.batch.kitchen.id = :kitchenId
              AND (:productId IS NULL OR m.batch.product.id = :productId)
              AND (:type IS NULL OR m.type = :type)
            ORDER BY m.createdAt DESC, m.id DESC
            """)
    List<StockMovement> search(@Param("kitchenId") Integer kitchenId, @Param("productId") Integer productId,
                               @Param("type") StockMovementType type, Pageable pageable);
}
