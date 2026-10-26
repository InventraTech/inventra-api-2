package com.inventra.api.core.service.prelist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.prelist.PreList;
import com.inventra.api.core.domain.prelist.PreListItem;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.PreListItemRepository;
import com.inventra.api.infrastructure.repository.PreListRepository;
import com.inventra.api.infrastructure.security.KitchenAccessGuard;

@ExtendWith(MockitoExtension.class)
class PreListServiceTest {

    @Mock private PreListRepository repository;
    @Mock private PreListItemRepository itemRepository;
    @Mock private KitchenAccessGuard accessGuard;

    @InjectMocks private PreListService service;

    private static PreList preList() {
        return PreList.builder().id(1).listDate(LocalDate.of(2026, 10, 26)).sourceId(7).build();
    }

    @Test
    void findByIdReturnsPreList() {
        PreList preList = preList();
        when(repository.findById(1)).thenReturn(Optional.of(preList));

        assertSame(preList, service.findById(1));
    }

    @Test
    void findByIdChecksAccessToTheKitchenOfThePreList() {
        PreList preList = preList();
        preList.setKitchen(Kitchen.builder().id(3).build());
        when(repository.findById(1)).thenReturn(Optional.of(preList));

        assertSame(preList, service.findById(1));
        verify(accessGuard).assertAccess(3);
    }

    @Test
    void findByIdDeniesPreListOfAnotherKitchen() {
        PreList preList = preList();
        preList.setKitchen(Kitchen.builder().id(3).build());
        when(repository.findById(1)).thenReturn(Optional.of(preList));
        doThrow(new AccessDeniedException("sem acesso")).when(accessGuard).assertAccess(3);

        assertThrows(AccessDeniedException.class, () -> service.findById(1));
    }

    @Test
    void findByIdSkipsAccessCheckWhenPreListHasNoKitchen() {
        when(repository.findById(1)).thenReturn(Optional.of(preList()));

        service.findById(1);

        verify(accessGuard, never()).assertAccess(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void findByIdThrowsWhenMissing() {
        when(repository.findById(99)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.findById(99));
    }

    @Test
    void listReturnsNewestFirst() {
        List<PreList> lists = List.of(preList());
        when(repository.findAllByOrderByListDateDescIdDesc()).thenReturn(lists);

        assertEquals(lists, service.list(null));
        verify(accessGuard, never()).assertAccess(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void listFiltersByKitchenAfterCheckingAccess() {
        List<PreList> lists = List.of(preList());
        when(repository.findByKitchenIdOrderByListDateDescIdDesc(3)).thenReturn(lists);

        assertEquals(lists, service.list(3));
        verify(accessGuard).assertAccess(3);
    }

    @Test
    void listDeniesKitchenOfAnotherUser() {
        doThrow(new AccessDeniedException("sem acesso")).when(accessGuard).assertAccess(4);

        assertThrows(AccessDeniedException.class, () -> service.list(4));
        verify(repository, never()).findByKitchenIdOrderByListDateDescIdDesc(4);
    }

    @Test
    void listItemsReturnsItemsOfExistingPreList() {
        List<PreListItem> items = List.of(PreListItem.builder().id(1).build());
        when(repository.findById(1)).thenReturn(Optional.of(preList()));
        when(itemRepository.findByPreListIdWithProductAndSupplier(1)).thenReturn(items);

        assertEquals(items, service.listItems(1));
    }

    @Test
    void listItemsThrowsWhenPreListIsMissing() {
        when(repository.findById(99)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.listItems(99));
        verify(itemRepository, never()).findByPreListIdWithProductAndSupplier(99);
    }
}
