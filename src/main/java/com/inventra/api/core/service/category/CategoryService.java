package com.inventra.api.core.service.category;

import java.util.List;

import org.springframework.stereotype.Service;

import com.inventra.api.core.domain.category.Category;
import com.inventra.api.core.service.category.model.request.CreateCategoryRequest;
import com.inventra.api.core.service.category.model.request.UpdateCategoryRequest;
import com.inventra.api.infrastructure.exception.BusinessRuleException;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.CategoryRepository;
import com.inventra.api.infrastructure.repository.ProductRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CategoryService implements CategoryUseCase {

    private final CategoryRepository repository;
    private final ProductRepository productRepository;

    @Override
    public Category create(CreateCategoryRequest request) {
        if (repository.existsByName(request.name())) {
            throw new BusinessRuleException("Já existe uma categoria com esse nome.");
        }

        Category category = Category.builder()
            .name(request.name())
            .description(request.description())
            .build();

        return repository.save(category);
    }

    @Override
    public Category findById(Integer id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Categoria não encontrada."));
    }

    @Override
    public List<Category> listAll() {
        return repository.findAll();
    }

    @Override
    public Category update(Integer id, UpdateCategoryRequest request) {
        Category category = findById(id);

        if (request.name() != null && !request.name().equals(category.getName())) {
            if (repository.existsByName(request.name())) {
                throw new BusinessRuleException("Já existe uma categoria com esse nome.");
            }
            category.setName(request.name());
        }
        if (request.description() != null) {
            category.setDescription(request.description());
        }

        return repository.save(category);
    }

    @Override
    public void delete(Integer id) {
        Category category = findById(id);

        if (productRepository.existsByCategoryId(id)) {
            throw new BusinessRuleException("Não é possível excluir: existem produtos vinculados a essa categoria.");
        }

        repository.delete(category);
    }
}
