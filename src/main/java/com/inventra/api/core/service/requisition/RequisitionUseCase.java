package com.inventra.api.core.service.requisition;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.inventra.api.core.service.requisition.model.request.AddRequisitionItemRequest;
import com.inventra.api.core.service.requisition.model.request.CreateRequisitionRequest;
import com.inventra.api.core.domain.requisition.Requisition;
import com.inventra.api.core.domain.requisition.RequisitionItem;
import com.inventra.api.core.domain.requisition.enums.RequisitionStatus;

public interface RequisitionUseCase {

    // nasce como rascunho (DRAFT): só o solicitante a vê até enviar
    Requisition create(CreateRequisitionRequest request);

    Requisition findById(Integer requisitionId);

    Requisition addItem(Integer requisitionId, AddRequisitionItemRequest request);

    Requisition removeItem(Integer requisitionId, Integer itemId);

    List<RequisitionItem> listItems(Integer requisitionId);

    // DRAFT -> UNDER_REVIEW (exige pelo menos um item) e avisa os supervisores
    Requisition submit(Integer requisitionId);

    // chama sp_approve_requisition com o usuário logado como aprovador; CONSUMPTION/TRANSFER
    // disparam baixa de estoque via StockBatchUseCase, PURCHASE não mexe no estoque
    Requisition approve(Integer requisitionId);

    // chama sp_reject_requisition, com o usuário logado como aprovador
    Requisition reject(Integer requisitionId, String reason);

    // chama sp_cancel_requisition, que devolve aos lotes o que a aprovação tinha baixado
    Requisition cancel(Integer requisitionId, String reason);

    // Filtros combinados: kitchenId nulo = cozinha do usuário logado; status e requesterId opcionais.
    // Rascunho de outra pessoa nunca aparece.
    List<Requisition> list(Integer kitchenId, RequisitionStatus status, UUID requesterId);

    // resumo dos itens de cada requisição (id -> resumo), numa consulta só
    Map<Integer, RequisitionItemsSummary> summarizeItems(Collection<Requisition> requisitions);
}
