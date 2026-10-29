package com.inventra.api.infrastructure.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.inventra.api.core.domain.supplier.Supplier;

public interface SupplierRepository extends JpaRepository<Supplier, Integer> {

    boolean existsByCnpj(String cnpj);

    List<Supplier> findByActiveTrue();

    // Fornecedores ativos da cozinha: já entregaram algum lote nela ou estão vinculados (tb_product_supplier) a
    // um produto ativo da cozinha, isto é, com parâmetro de estoque ou lote nela (o critério de
    // ProductRepository.findKitchenProducts). O vínculo é global: se duas cozinhas têm o mesmo produto, o
    // fornecedor dele aparece nas duas.
    @Query("""
            SELECT s FROM Supplier s
            WHERE s.active = true
              AND (EXISTS (SELECT 1 FROM StockBatch sb WHERE sb.supplier = s AND sb.kitchen.id = :kitchenId)
                   OR EXISTS (SELECT 1 FROM ProductSupplier ps
                              WHERE ps.supplier = s AND ps.product.active = true
                                AND (EXISTS (SELECT 1 FROM ProductKitchenParameter pkp
                                             WHERE pkp.product = ps.product AND pkp.kitchen.id = :kitchenId)
                                     OR EXISTS (SELECT 1 FROM StockBatch sb2
                                                WHERE sb2.product = ps.product AND sb2.kitchen.id = :kitchenId))))
            ORDER BY s.legalName
            """)
    List<Supplier> findActiveByKitchen(@Param("kitchenId") Integer kitchenId);

}
