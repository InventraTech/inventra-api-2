package com.inventra.api.core.domain.alert;

// Tipos de alerta gerados pela API. tb_alert.type é texto livre: os alertas de estoque e validade
// (STOCK, EXPIRATION, EXPIRING) nascem no banco, pelas triggers e procedures.
public final class AlertType {

    public static final String ACCESS_REQUEST = "ACCESS_REQUEST";
    public static final String REQUISITION_SUBMITTED = "REQUISITION_SUBMITTED";
    public static final String REQUISITION_APPROVED = "REQUISITION_APPROVED";
    public static final String REQUISITION_REJECTED = "REQUISITION_REJECTED";
    public static final String UNREGISTERED_PRODUCT = "UNREGISTERED_PRODUCT";

    private AlertType() {
    }
}
