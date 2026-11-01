package com.inventra.api.core.service.supplier;

import java.util.List;

import com.inventra.api.core.domain.supplier.Supplier;
import com.inventra.api.core.service.supplier.model.request.CreateSupplierRequest;
import com.inventra.api.core.service.supplier.model.request.UpdateSupplierRequest;

public interface SupplierUseCase {

    Supplier create(CreateSupplierRequest request);

    Supplier findById(Integer id);

    List<Supplier> list(boolean active);

    // fornecedores da cozinha (já entregaram lote nela ou estão vinculados a um produto dela) com o status pedido
    List<Supplier> listByKitchen(Integer kitchenId, boolean active);

    Supplier update(Integer id, UpdateSupplierRequest request);

    Supplier updatePhoto(Integer id, byte[] content);

    Supplier removePhoto(Integer id);

    void activate(Integer id);

    void deactivate(Integer id);
}
