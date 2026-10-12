package com.inventra.api.core.domain.stock.enums;

// Motivo de uma baixa (WRITE_OFF). REQUISITION é gravado só pela aprovação de requisição.
public enum StockMovementReason {
    USAGE,
    LOSS,
    EXPIRED,
    REQUISITION
}
