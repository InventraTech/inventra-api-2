package com.inventra.api.core.service.productqueue;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inventra.api.core.service.product.ProductUseCase;
import com.inventra.api.core.service.product.model.request.CreateProductRequest;
import com.inventra.api.core.service.productqueue.model.ProductRegistrationJob;
import com.inventra.api.infrastructure.repository.ProductRepository;
import com.inventra.api.infrastructure.exception.BusinessRuleException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductRegistrationProcessor {
    private final ProductRepository repository;
    private final ProductUseCase productUseCase;

    /** Retomadas após commit no PostgreSQL reutilizam o produto, sem cadastrar duas vezes. */
    @Transactional
    public Integer process(ProductRegistrationJob job) {
        var completed = repository.findByRegistrationEventId(job.eventId());
        if (completed.isPresent()) return completed.get().getId();
        var request = job.request();
        var existing = repository.findByBarcode(request.barcode());
        if (existing.isPresent()) {
            var product = existing.get();
            if (job.eventId().equals(product.getRegistrationEventId())) return product.getId();
            throw new BusinessRuleException("Código de barras já cadastrado.");
        }
        var product = productUseCase.create(new CreateProductRequest(request.name(), request.brand(),
                request.categoryId(), request.unitId(), request.barcode(), request.photoUrl()));
        product.setRegistrationEventId(job.eventId());
        return repository.save(product).getId();
    }
}
