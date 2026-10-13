package com.inventra.api.infrastructure.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.inventra.api.core.domain.category.Category;
import com.inventra.api.core.service.category.CategoryUseCase;
import com.inventra.api.core.service.category.model.request.CreateCategoryRequest;
import com.inventra.api.core.service.category.model.request.UpdateCategoryRequest;
import com.inventra.api.core.service.category.model.response.CategoryResponse;
import com.inventra.api.infrastructure.security.Roles;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@Tag(name = "Categorias", description = "Categorias de produtos")
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryUseCase useCase;

    @Operation(summary = "Cria uma categoria")
    @PreAuthorize(Roles.SUPERVISOR)
    @PostMapping
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CreateCategoryRequest request) {
        Category created = useCase.create(request);
        CategoryResponse response = CategoryResponse.fromEntity(created);
        return ResponseEntity.created(URI.create("/api/categories/" + response.id())).body(response);
    }

    @Operation(summary = "Busca uma categoria pelo id")
    @GetMapping("/{id}")
    public ResponseEntity<CategoryResponse> findById(@PathVariable Integer id) {
        return ResponseEntity.ok(CategoryResponse.fromEntity(useCase.findById(id)));
    }

    @Operation(summary = "Lista todas as categorias")
    @GetMapping
    public ResponseEntity<List<CategoryResponse>> listAll() {
        List<CategoryResponse> responses = useCase.listAll().stream()
                .map(CategoryResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @Operation(summary = "Atualiza uma categoria")
    @PreAuthorize(Roles.SUPERVISOR)
    @PutMapping("/{id}")
    public ResponseEntity<CategoryResponse> update(@PathVariable Integer id,
                                                    @Valid @RequestBody UpdateCategoryRequest request) {
        Category updated = useCase.update(id, request);
        return ResponseEntity.ok(CategoryResponse.fromEntity(updated));
    }

    @Operation(summary = "Exclui uma categoria")
    @PreAuthorize(Roles.SUPERVISOR)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Integer id) {
        useCase.delete(id);
        return ResponseEntity.noContent().build();
    }
}
