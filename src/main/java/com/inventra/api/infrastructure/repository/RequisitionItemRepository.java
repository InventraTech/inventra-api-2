package com.inventra.api.infrastructure.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.inventra.api.core.domain.requisition.RequisitionItem;

public interface RequisitionItemRepository extends JpaRepository<RequisitionItem, Integer> {

    List<RequisitionItem> findByRequisitionId(Integer requisitionId);

    long countByRequisitionId(Integer requisitionId);

    // Itens de várias requisições numa consulta só (com o produto), na ordem em que foram adicionados.
    @Query("""
            SELECT i FROM RequisitionItem i JOIN FETCH i.product
            WHERE i.requisition.id IN :requisitionIds
            ORDER BY i.id
            """)
    List<RequisitionItem> findByRequisitionIdsWithProduct(@Param("requisitionIds") Collection<Integer> requisitionIds);

}
