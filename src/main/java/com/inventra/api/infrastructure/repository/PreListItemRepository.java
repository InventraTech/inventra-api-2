package com.inventra.api.infrastructure.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.inventra.api.core.domain.prelist.PreListItem;

public interface PreListItemRepository extends JpaRepository<PreListItem, Integer> {

    // Itens da pré-lista com produto e fornecedor numa consulta só, na ordem em que foram gravados.
    @Query("""
            SELECT i FROM PreListItem i JOIN FETCH i.product JOIN FETCH i.supplier
            WHERE i.preList.id = :preListId
            ORDER BY i.id
            """)
    List<PreListItem> findByPreListIdWithProductAndSupplier(@Param("preListId") Integer preListId);

}
