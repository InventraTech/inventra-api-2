package com.inventra.api.infrastructure.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.inventra.api.core.domain.requisition.Requisition;
import com.inventra.api.core.domain.requisition.enums.RequisitionStatus;

public interface RequisitionRepository extends JpaRepository<Requisition, Integer> {

    List<Requisition> findByKitchenId(Integer kitchenId);

    List<Requisition> findByStatus(RequisitionStatus status);

    List<Requisition> findByRequesterId(UUID requesterId);

    // Procedures criadas em V3__business_rules.sql. clearAutomatically descarta as entidades em memória,
    // já que a procedure (e os triggers) alteram a linha direto no banco.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "CALL sp_approve_requisition(:requisitionId, :approverId)", nativeQuery = true)
    void callApproveRequisition(@Param("requisitionId") Integer requisitionId, @Param("approverId") UUID approverId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "CALL sp_reject_requisition(:requisitionId, :approverId, :reason)", nativeQuery = true)
    void callRejectRequisition(@Param("requisitionId") Integer requisitionId, @Param("approverId") UUID approverId,
                               @Param("reason") String reason);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "CALL sp_cancel_requisition(:requisitionId, :reason)", nativeQuery = true)
    void callCancelRequisition(@Param("requisitionId") Integer requisitionId, @Param("reason") String reason);

}
