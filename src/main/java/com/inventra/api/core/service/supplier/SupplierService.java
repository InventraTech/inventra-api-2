package com.inventra.api.core.service.supplier;

import java.util.List;

import org.springframework.stereotype.Service;

import com.inventra.api.core.domain.supplier.Supplier;
import com.inventra.api.core.service.supplier.model.request.CreateSupplierRequest;
import com.inventra.api.core.service.supplier.model.request.UpdateSupplierRequest;
import com.inventra.api.infrastructure.exception.BusinessRuleException;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.StockBatchRepository;
import com.inventra.api.infrastructure.repository.SupplierRepository;
import com.inventra.api.infrastructure.security.KitchenAccessGuard;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SupplierService implements SupplierUseCase {

    private final SupplierRepository repository;
    private final StockBatchRepository stockBatchRepository;
    private final KitchenAccessGuard accessGuard;

    @Override
    public Supplier create(CreateSupplierRequest request) {
        if (repository.existsByCnpj(request.cnpj())) {
            throw new BusinessRuleException("Já existe um fornecedor com esse CNPJ.");
        }

        Supplier supplier = Supplier.builder()
            .legalName(request.legalName())
            .cnpj(request.cnpj())
            .email(request.email())
            .whatsapp(request.whatsapp())
            .rating(request.rating())
            .active(true)
            .build();

        return repository.save(supplier);
    }

    @Override
    public Supplier findById(Integer id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado."));
    }

    @Override
    public List<Supplier> listActive() {
        return repository.findByActiveTrue();
    }

    @Override
    public List<Supplier> listActiveByKitchen(Integer kitchenId) {
        accessGuard.assertAccess(kitchenId);
        return stockBatchRepository.findActiveSuppliersByKitchen(kitchenId);
    }

    @Override
    public Supplier update(Integer id, UpdateSupplierRequest request) {
        Supplier supplier = findById(id);

        if (request.legalName() != null) {
            supplier.setLegalName(request.legalName());
        }
        if (request.email() != null) {
            supplier.setEmail(request.email());
        }
        if (request.whatsapp() != null) {
            supplier.setWhatsapp(request.whatsapp());
        }
        if (request.rating() != null) {
            supplier.setRating(request.rating());
        }

        return repository.save(supplier);
    }

    @Override
    public void activate(Integer id) {
        Supplier supplier = findById(id);
        supplier.setActive(true);
        repository.save(supplier);
    }

    @Override
    public void deactivate(Integer id) {
        Supplier supplier = findById(id);
        supplier.setActive(false);
        repository.save(supplier);
    }
}
