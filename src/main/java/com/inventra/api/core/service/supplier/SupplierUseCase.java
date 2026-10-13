package com.inventra.api.core.service.supplier;

import java.util.List;

import com.inventra.api.core.domain.supplier.Supplier;
import com.inventra.api.core.service.supplier.model.request.CreateSupplierRequest;
import com.inventra.api.core.service.supplier.model.request.UpdateSupplierRequest;

public interface SupplierUseCase {

    Supplier create(CreateSupplierRequest request);

    Supplier findById(Integer id);

    List<Supplier> listActive();

    // fornecedores ativos que já entregaram algum lote na cozinha
    List<Supplier> listActiveByKitchen(Integer kitchenId);

    Supplier update(Integer id, UpdateSupplierRequest request);

    void activate(Integer id);

    void deactivate(Integer id);
}
