package com.inventra.api.core.service.productqueue;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inventra.api.core.service.product.ProductUseCase;
import com.inventra.api.core.service.product.model.request.CreateProductRequest;
import com.inventra.api.core.service.productqueue.model.ProductRegistrationJob;
import com.inventra.api.infrastructure.repository.ProductRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductRegistrationProcessor {
    private final ProductRepository repository;
    private final ProductUseCase productUseCase;

    /**
     * O barcode é a chave de idempotência: a entrada na fila já recusa barcode cadastrado, então
     * achá-lo aqui é uma retomada após commit no PostgreSQL (ou um cadastro direto concorrente)
     * e o job devolve o produto existente, sem cadastrar duas vezes.
     */
    @Transactional
    public Integer process(ProductRegistrationJob job) {
        var request = job.request();
        var existing = repository.findByBarcode(request.barcode());
        if (existing.isPresent()) return existing.get().getId();
        return productUseCase.create(new CreateProductRequest(request.name(), request.brand(),
                request.categoryId(), request.unitId(), request.barcode())).getId();
    }
}
