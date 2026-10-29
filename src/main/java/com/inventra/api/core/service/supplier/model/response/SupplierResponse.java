package com.inventra.api.core.service.supplier.model.response;

import java.time.LocalDateTime;

import com.inventra.api.core.domain.supplier.Supplier;

public record SupplierResponse(
        Integer id,
        String legalName,
        String cnpj,
        String email,
        String whatsapp,
        Integer rating,
        String photoUrl,
        Boolean active,
        LocalDateTime createdAt
) {
    public static SupplierResponse fromEntity(Supplier supplier) {
        return new SupplierResponse(
                supplier.getId(),
                supplier.getLegalName(),
                supplier.getCnpj(),
                supplier.getEmail(),
                supplier.getWhatsapp(),
                supplier.getRating(),
                supplier.getPhotoUrl(),
                supplier.getActive(),
                supplier.getCreatedAt()
        );
    }
}
