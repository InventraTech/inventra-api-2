package com.inventra.api.core.service.productqueue;

import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.inventra.api.core.domain.supplier.Supplier;
import com.inventra.api.core.service.productqueue.model.BarcodeRegistrationRequest;
import com.inventra.api.core.service.productqueue.model.ProductRegistrationJob;
import com.inventra.api.core.service.productqueue.model.ProductRegistrationResponse;
import com.inventra.api.infrastructure.exception.BusinessRuleException;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.redis.queue.RedisProductRegistrationQueue;
import com.inventra.api.infrastructure.repository.ProductRepository;
import com.inventra.api.infrastructure.repository.SupplierRepository;
import com.inventra.api.infrastructure.security.KitchenAccessGuard;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductRegistrationService {
    private final RedisProductRegistrationQueue queue;
    private final KitchenAccessGuard accessGuard;
    private final ProductRepository products;
    private final SupplierRepository suppliers;

    public ProductRegistrationResponse enqueue(BarcodeRegistrationRequest request) {
        if (products.existsByBarcode(request.barcode())) {
            throw new BusinessRuleException("Código de barras já cadastrado.");
        }
        // o consumer revalida no cadastro; aqui é para o usuário saber na hora, e não pelo status FAILED depois
        Supplier supplier = suppliers.findById(request.supplierId())
                .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado."));
        if (!Boolean.TRUE.equals(supplier.getActive())) {
            throw new BusinessRuleException("Fornecedor desativado: escolha um fornecedor ativo para o produto.");
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
