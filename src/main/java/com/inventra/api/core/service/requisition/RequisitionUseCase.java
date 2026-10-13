package com.inventra.api.core.service.requisition;

import java.util.List;
import java.util.UUID;

import com.inventra.api.core.service.requisition.model.request.AddRequisitionItemRequest;
import com.inventra.api.core.service.requisition.model.request.CreateRequisitionRequest;
import com.inventra.api.core.domain.requisition.Requisition;
import com.inventra.api.core.domain.requisition.RequisitionItem;
import com.inventra.api.core.domain.requisition.enums.RequisitionStatus;

public interface RequisitionUseCase {

    Requisition create(CreateRequisitionRequest request);

    Requisition addItem(Integer requisitionId, AddRequisitionItemRequest request);

    Requisition removeItem(Integer requisitionId, Integer itemId);

    List<RequisitionItem> listItems(Integer requisitionId);

    Requisition submit(Integer requisitionId);

    // chama sp_approve_requisition e dispara baixa de estoque via StockBatchUseCase
    Requisition approve(Integer requisitionId, UUID approverId);

    // chama sp_reject_requisition, com o usuário logado como aprovador
    Requisition reject(Integer requisitionId, String reason);

    // chama sp_cancel_requisition
    Requisition cancel(Integer requisitionId, String reason);

    List<Requisition> listByKitchen(Integer kitchenId);

    List<Requisition> listByStatus(RequisitionStatus status);

    List<Requisition> listByRequester(UUID requesterId);
}
