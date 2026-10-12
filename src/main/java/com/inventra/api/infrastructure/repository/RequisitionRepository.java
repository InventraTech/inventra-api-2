package com.inventra.api.infrastructure.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.inventra.api.core.domain.requisition.Requisition;
import com.inventra.api.core.domain.requisition.enums.RequisitionStatus;

// Listagens sempre filtradas pela cozinha no banco (e não carregando tudo pra filtrar em memória).
public interface RequisitionRepository extends JpaRepository<Requisition, Integer> {

    @EntityGraph(attributePaths = {"kitchen", "requester", "approver"})
    List<Requisition> findByKitchenId(Integer kitchenId);

    @EntityGraph(attributePaths = {"kitchen", "requester", "approver"})
    List<Requisition> findByKitchenIdAndStatus(Integer kitchenId, RequisitionStatus status);

    @EntityGraph(attributePaths = {"kitchen", "requester", "approver"})
    List<Requisition> findByKitchenIdAndRequesterId(Integer kitchenId, UUID requesterId);

    @EntityGraph(attributePaths = {"kitchen", "requester", "approver"})
    List<Requisition> findByKitchenIdAndStatusAndRequesterId(Integer kitchenId, RequisitionStatus status,
                                                             UUID requesterId);

    // Procedures criadas em V002__business_rules.sql (sp_cancel_requisition foi recriada em
    // V009__requisition_draft.sql). clearAutomatically descarta as entidades em memória,
    // já que a procedure (e os triggers) alteram a linha direto no banco.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "CALL sp_approve_requisition(:requisitionId, :approverId)", nativeQuery = true)
    void callApproveRequisition(@Param("requisitionId") Integer requisitionId, @Param("approverId") UUID approverId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "CALL sp_reject_requisition(:requisitionId, :approverId, :reason)", nativeQuery = true)
    void callRejectRequisition(@Param("requisitionId") Integer requisitionId, @Param("approverId") UUID approverId,
                               @Param("reason") String reason);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "CALL sp_cancel_requisition(:requisitionId, :reason, :userId)", nativeQuery = true)
    void callCancelRequisition(@Param("requisitionId") Integer requisitionId, @Param("reason") String reason,
                               @Param("userId") UUID userId);
}
