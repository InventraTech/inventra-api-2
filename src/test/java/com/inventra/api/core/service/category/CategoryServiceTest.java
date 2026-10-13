package com.inventra.api.core.service.category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.inventra.api.core.domain.category.Category;
import com.inventra.api.core.service.category.model.request.CreateCategoryRequest;
import com.inventra.api.core.service.category.model.request.UpdateCategoryRequest;
import com.inventra.api.infrastructure.exception.BusinessRuleException;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.CategoryRepository;
import com.inventra.api.infrastructure.repository.ProductRepository;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock private CategoryRepository repository;
    @Mock private ProductRepository productRepository;

    @InjectMocks private CategoryService service;

    private static Category category() {
        return Category.builder().id(1).name("Grãos").description("Arroz e feijão").build();
    }

    @Test
    void createSavesCategory() {
        when(repository.existsByName("Grãos")).thenReturn(false);
        when(repository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

        Category result = service.create(new CreateCategoryRequest("Grãos", "Arroz e feijão"));

        assertEquals("Grãos", result.getName());
        assertEquals("Arroz e feijão", result.getDescription());
    }

    @Test
    void createRejectsDuplicateName() {
        when(repository.existsByName("Grãos")).thenReturn(true);

        assertThrows(BusinessRuleException.class,
                () -> service.create(new CreateCategoryRequest("Grãos", null)));
        verify(repository, never()).save(any());
    }

    @Test
    void findByIdThrowsWhenMissing() {
        when(repository.findById(9)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.findById(9));
    }

    @Test
    void listAllReturnsRepositoryContent() {
        List<Category> all = List.of(category());
        when(repository.findAll()).thenReturn(all);

        assertSame(all, service.listAll());
    }

    @Test
    void updateChangesOnlyProvidedFields() {
        Category existing = category();
        when(repository.findById(1)).thenReturn(Optional.of(existing));
        when(repository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

        Category result = service.update(1, new UpdateCategoryRequest(null, "Nova descrição"));

        assertEquals("Grãos", result.getName());
        assertEquals("Nova descrição", result.getDescription());
    }

    @Test
    void updateRejectsNameUsedByAnotherCategory() {
        when(repository.findById(1)).thenReturn(Optional.of(category()));
        when(repository.existsByName("Bebidas")).thenReturn(true);

        assertThrows(BusinessRuleException.class,
                () -> service.update(1, new UpdateCategoryRequest("Bebidas", null)));
        verify(repository, never()).save(any());
    }

    @Test
    void updateKeepingSameNameDoesNotCheckDuplicate() {
        when(repository.findById(1)).thenReturn(Optional.of(category()));
        when(repository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(1, new UpdateCategoryRequest("Grãos", null));

        verify(repository, never()).existsByName(any());
    }

    @Test
    void deleteRemovesCategoryWithoutProducts() {
        Category existing = category();
        when(repository.findById(1)).thenReturn(Optional.of(existing));
        when(productRepository.existsByCategoryId(1)).thenReturn(false);

        service.delete(1);

        verify(repository).delete(existing);
    }

    @Test
    void deleteIsBlockedWhenProductsAreLinked() {
        when(repository.findById(1)).thenReturn(Optional.of(category()));
        when(productRepository.existsByCategoryId(1)).thenReturn(true);

        assertThrows(BusinessRuleException.class, () -> service.delete(1));
        verify(repository, never()).delete(any());
    }
}
