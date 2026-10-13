package com.inventra.api.infrastructure.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.inventra.api.core.domain.product.ProductKitchenParameter;
import com.inventra.api.core.domain.product.ProductKitchenParameterId;

public interface ProductKitchenParameterRepository extends JpaRepository<ProductKitchenParameter, ProductKitchenParameterId> {

    @EntityGraph(attributePaths = {"product", "kitchen"})
    List<ProductKitchenParameter> findByProduct_IdAndKitchen_Id(Integer productId, Integer kitchenId);

    // Parâmetros da cozinha cujo saldo utilizável (lotes ativos e não vencidos) está abaixo do mínimo,
    // calculado no banco numa consulta só.
    @EntityGraph(attributePaths = {"product", "product.unit", "kitchen"})
    @Query("""
            SELECT p FROM ProductKitchenParameter p
            WHERE p.kitchen.id = :kitchenId
              AND p.minStock > (
                  SELECT COALESCE(SUM(sb.currentQuantity), 0) FROM StockBatch sb
                  WHERE sb.product = p.product AND sb.kitchen = p.kitchen AND sb.status = 'ACTIVE'
                    AND (sb.expirationDate IS NULL OR sb.expirationDate >= :today))
            """)
    List<ProductKitchenParameter> findBelowMinimum(@Param("kitchenId") Integer kitchenId, @Param("today") LocalDate today);
}
