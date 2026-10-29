package com.inventra.api.infrastructure.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.inventra.api.core.domain.product.ProductSupplier;
import com.inventra.api.core.domain.product.ProductSupplierId;

public interface ProductSupplierRepository extends JpaRepository<ProductSupplier, ProductSupplierId> {

    // Do vínculo gravado por último para o mais antigo (o app mostra o primeiro como "o fornecedor do produto").
    // Os vínculos anteriores à V020 têm todos a mesma data: o desempate pelo fornecedor deixa a ordem estável.
    @Query("""
            SELECT ps FROM ProductSupplier ps
            JOIN FETCH ps.supplier s
            WHERE ps.product.id = :productId
            ORDER BY ps.updatedAt DESC, s.id DESC
            """)
    List<ProductSupplier> findByProductIdNewestFirst(@Param("productId") Integer productId);

}
