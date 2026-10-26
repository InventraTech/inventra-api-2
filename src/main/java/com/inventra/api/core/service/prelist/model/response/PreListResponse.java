package com.inventra.api.core.service.prelist.model.response;

import java.time.LocalDate;
import java.util.List;

import com.inventra.api.core.domain.prelist.PreList;
import com.inventra.api.core.domain.prelist.PreListItem;

public record PreListResponse(
        Integer id,
        LocalDate listDate,
        Integer sourceId,
        Integer kitchenId,
        List<PreListItemResponse> items
) {
    // listagem: só o cabeçalho, sem os itens
    public static PreListResponse summary(PreList preList) {
        return new PreListResponse(preList.getId(), preList.getListDate(), preList.getSourceId(), kitchenId(preList), null);
    }

    private static Integer kitchenId(PreList preList) {
        return preList.getKitchen() != null ? preList.getKitchen().getId() : null;
    }

    public static PreListResponse fromEntity(PreList preList, List<PreListItem> items) {
        return new PreListResponse(
                preList.getId(),
                preList.getListDate(),
                preList.getSourceId(),
                kitchenId(preList),
                items.stream().map(PreListItemResponse::fromEntity).toList()
        );
    }
}
