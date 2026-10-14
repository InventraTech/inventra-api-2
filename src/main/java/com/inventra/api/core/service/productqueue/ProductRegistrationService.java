package com.inventra.api.core.service.productqueue;

import java.util.UUID;
import com.inventra.api.infrastructure.repository.ProductRepository;
import com.inventra.api.infrastructure.exception.BusinessRuleException;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.inventra.api.core.service.productqueue.model.BarcodeRegistrationRequest;
import com.inventra.api.core.service.productqueue.model.ProductRegistrationJob;
import com.inventra.api.core.service.productqueue.model.ProductRegistrationResponse;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.redis.queue.RedisProductRegistrationQueue;
import com.inventra.api.infrastructure.security.KitchenAccessGuard;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductRegistrationService {
    private final RedisProductRegistrationQueue queue;
    private final KitchenAccessGuard accessGuard;
    private final ProductRepository products;

    public ProductRegistrationResponse enqueue(BarcodeRegistrationRequest request) {
        if (products.existsByBarcode(request.barcode())) {
            throw new BusinessRuleException("Código de barras já cadastrado.");
        }
        ProductRegistrationJob job = ProductRegistrationJob.queued(accessGuard.currentUser().getId(), request);
        queue.enqueue(job);
        return ProductRegistrationResponse.from(job);
    }

    public ProductRegistrationResponse find(UUID eventId) {
        ProductRegistrationJob job = queue.find(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Solicitação de cadastro não encontrada."));
        if (!job.userId().equals(accessGuard.currentUser().getId())) {
            throw new AccessDeniedException("Você não tem acesso a essa solicitação.");
        }
        return ProductRegistrationResponse.from(job);
    }
}
