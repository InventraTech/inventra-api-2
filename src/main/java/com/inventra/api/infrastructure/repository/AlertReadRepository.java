package com.inventra.api.infrastructure.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.inventra.api.core.domain.alert.AlertRead;
import com.inventra.api.core.domain.alert.AlertReadId;

public interface AlertReadRepository extends JpaRepository<AlertRead, AlertReadId> {

    // Dos alertas informados, quais o usuário já leu.
    @Query("""
            SELECT r.id.alertId FROM AlertRead r
            WHERE r.id.userId = :userId AND r.id.alertId IN :alertIds
            """)
    List<Integer> findReadAlertIds(@Param("userId") UUID userId, @Param("alertIds") Collection<Integer> alertIds);

    @Transactional
    @Modifying
    @Query("DELETE FROM AlertRead r WHERE r.id.alertId = :alertId")
    void deleteByAlertId(@Param("alertId") Integer alertId);
}
