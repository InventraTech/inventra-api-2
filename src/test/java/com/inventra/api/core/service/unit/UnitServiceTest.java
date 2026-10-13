package com.inventra.api.core.service.unit;

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

import com.inventra.api.core.domain.unit.Unit;
import com.inventra.api.core.service.unit.model.request.CreateUnitRequest;
import com.inventra.api.core.service.unit.model.request.UpdateUnitRequest;
import com.inventra.api.infrastructure.exception.BusinessRuleException;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.UnitRepository;
import com.inventra.api.infrastructure.repository.ProductRepository;

@ExtendWith(MockitoExtension.class)
class UnitServiceTest {

    @Mock private UnitRepository repository;
    @Mock private ProductRepository productRepository;

    @InjectMocks private UnitService service;

    private static Unit unit() {
        return Unit.builder().id(1).symbol("kg").description("Quilograma").build();
    }

    @Test
    void createSavesUnit() {
        when(repository.existsBySymbol("kg")).thenReturn(false);
        when(repository.save(any(Unit.class))).thenAnswer(inv -> inv.getArgument(0));

        Unit result = service.create(new CreateUnitRequest("kg", "Quilograma"));

        assertEquals("kg", result.getSymbol());
        assertEquals("Quilograma", result.getDescription());
    }

    @Test
    void createRejectsDuplicateName() {
        when(repository.existsBySymbol("kg")).thenReturn(true);

        assertThrows(BusinessRuleException.class,
                () -> service.create(new CreateUnitRequest("kg", null)));
        verify(repository, never()).save(any());
    }

    @Test
    void findByIdThrowsWhenMissing() {
        when(repository.findById(9)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.findById(9));
    }

    @Test
    void listAllReturnsRepositoryContent() {
        List<Unit> all = List.of(unit());
        when(repository.findAll()).thenReturn(all);

        assertSame(all, service.listAll());
    }

    @Test
    void updateChangesOnlyProvidedFields() {
        Unit existing = unit();
        when(repository.findById(1)).thenReturn(Optional.of(existing));
        when(repository.save(any(Unit.class))).thenAnswer(inv -> inv.getArgument(0));

        Unit result = service.update(1, new UpdateUnitRequest(null, "Nova descrição"));

        assertEquals("kg", result.getSymbol());
        assertEquals("Nova descrição", result.getDescription());
    }

    @Test
    void updateRejectsNameUsedByAnotherUnit() {
        when(repository.findById(1)).thenReturn(Optional.of(unit()));
        when(repository.existsBySymbol("l")).thenReturn(true);

        assertThrows(BusinessRuleException.class,
                () -> service.update(1, new UpdateUnitRequest("l", null)));
        verify(repository, never()).save(any());
    }

    @Test
    void updateKeepingSameNameDoesNotCheckDuplicate() {
        when(repository.findById(1)).thenReturn(Optional.of(unit()));
        when(repository.save(any(Unit.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(1, new UpdateUnitRequest("kg", null));

        verify(repository, never()).existsBySymbol(any());
    }

    @Test
    void deleteRemovesUnitWithoutProducts() {
        Unit existing = unit();
        when(repository.findById(1)).thenReturn(Optional.of(existing));
        when(productRepository.existsByUnitId(1)).thenReturn(false);

        service.delete(1);

        verify(repository).delete(existing);
    }

    @Test
    void deleteIsBlockedWhenProductsAreLinked() {
        when(repository.findById(1)).thenReturn(Optional.of(unit()));
        when(productRepository.existsByUnitId(1)).thenReturn(true);

        assertThrows(BusinessRuleException.class, () -> service.delete(1));
        verify(repository, never()).delete(any());
    }
}
