package com.inventra.api.core.service.prelist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.inventra.api.core.domain.prelist.PreList;
import com.inventra.api.core.domain.prelist.PreListItem;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.PreListItemRepository;
import com.inventra.api.infrastructure.repository.PreListRepository;

@ExtendWith(MockitoExtension.class)
class PreListServiceTest {

    @Mock private PreListRepository repository;
    @Mock private PreListItemRepository itemRepository;

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
    void findByIdThrowsWhenMissing() {
        when(repository.findById(99)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.findById(99));
    }

    @Test
    void listReturnsNewestFirst() {
        List<PreList> lists = List.of(preList());
        when(repository.findAllByOrderByListDateDescIdDesc()).thenReturn(lists);

        assertEquals(lists, service.list());
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
