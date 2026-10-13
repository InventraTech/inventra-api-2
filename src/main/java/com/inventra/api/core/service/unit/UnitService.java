package com.inventra.api.core.service.unit;

import java.util.List;

import org.springframework.stereotype.Service;

import com.inventra.api.core.domain.unit.Unit;
import com.inventra.api.core.service.unit.model.request.CreateUnitRequest;
import com.inventra.api.core.service.unit.model.request.UpdateUnitRequest;
import com.inventra.api.infrastructure.exception.BusinessRuleException;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.ProductRepository;
import com.inventra.api.infrastructure.repository.UnitRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UnitService implements UnitUseCase {

    private final UnitRepository repository;
    private final ProductRepository productRepository;

    @Override
    public Unit create(CreateUnitRequest request) {
        if (repository.existsBySymbol(request.symbol())) {
            throw new BusinessRuleException("Já existe uma unidade de medida com esse símbolo.");
        }

        Unit unit = Unit.builder()
            .symbol(request.symbol())
            .description(request.description())
            .build();

        return repository.save(unit);
    }

    @Override
    public Unit findById(Integer id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Unidade de medida não encontrada."));
    }

    @Override
    public List<Unit> listAll() {
        return repository.findAll();
    }

    @Override
    public Unit update(Integer id, UpdateUnitRequest request) {
        Unit unit = findById(id);

        if (request.symbol() != null && !request.symbol().equals(unit.getSymbol())) {
            if (repository.existsBySymbol(request.symbol())) {
                throw new BusinessRuleException("Já existe uma unidade de medida com esse símbolo.");
            }
            unit.setSymbol(request.symbol());
        }
        if (request.description() != null) {
            unit.setDescription(request.description());
        }

        return repository.save(unit);
    }

    @Override
    public void delete(Integer id) {
        Unit unit = findById(id);

        if (productRepository.existsByUnitId(id)) {
            throw new BusinessRuleException("Não é possível excluir: existem produtos vinculados a essa unidade de medida.");
        }

        repository.delete(unit);
    }
}
