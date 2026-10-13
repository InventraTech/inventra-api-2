package com.inventra.api.core.service.requisition;

import java.math.BigDecimal;
import java.util.List;

// Resumo dos itens de uma requisição para o card da listagem: evita uma chamada de itens por requisição.
public record RequisitionItemsSummary(
        int itemCount,
        // soma de quantidade x preço estimado; item sem preço conta zero
        BigDecimal estimatedTotal,
        // nomes dos primeiros itens (no máximo MAX_ITEM_NAMES)
        List<String> itemNames
) {
    public static final int MAX_ITEM_NAMES = 3;

    public static final RequisitionItemsSummary EMPTY = new RequisitionItemsSummary(0, BigDecimal.ZERO.setScale(2), List.of());
}
