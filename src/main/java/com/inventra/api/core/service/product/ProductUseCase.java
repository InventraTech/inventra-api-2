package com.inventra.api.core.service.product;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.inventra.api.core.service.product.model.request.CreateProductRequest;
import com.inventra.api.core.service.product.model.request.LinkSupplierRequest;
import com.inventra.api.core.service.product.model.request.SetKitchenParametersRequest;
import com.inventra.api.core.service.product.model.request.UpdateProductRequest;
import com.inventra.api.core.service.product.model.response.ProductKitchenParameterResponse;
import com.inventra.api.core.service.product.model.response.ProductSupplierResponse;
import com.inventra.api.core.domain.product.Product;

public interface ProductUseCase {

    Product create(CreateProductRequest request);

    Product findById(Integer id);

    Page<Product> search(String name, Integer categoryId, Boolean active, Pageable pageable);

    Product update(Integer id, UpdateProductRequest request);

    Product updatePhoto(Integer id, byte[] content);

    Product removePhoto(Integer id);

    void activate(Integer id);

    void deactivate(Integer id);

    // pode nascer aqui e depois virar service próprio
    void linkSupplier(Integer productId, LinkSupplierRequest request);

    void setKitchenParameters(Integer productId, SetKitchenParametersRequest request);

    List<ProductSupplierResponse> listSuppliers(Integer productId);

    List<ProductKitchenParameterResponse> listKitchenParameters(Integer productId);
}
