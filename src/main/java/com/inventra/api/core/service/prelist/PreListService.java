package com.inventra.api.core.service.prelist;

import java.util.List;

import org.springframework.stereotype.Service;

import com.inventra.api.core.domain.prelist.PreList;
import com.inventra.api.core.domain.prelist.PreListItem;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.PreListItemRepository;
import com.inventra.api.infrastructure.repository.PreListRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PreListService implements PreListUseCase {

    private final PreListRepository repository;
    private final PreListItemRepository itemRepository;

    @Override
    public PreList findById(Integer id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Pré-lista não encontrada."));
    }

    @Override
    public List<PreList> list() {
        return repository.findAllByOrderByListDateDescIdDesc();
    }

    @Override
    public List<PreListItem> listItems(Integer preListId) {
        findById(preListId);
        return itemRepository.findByPreListIdWithProductAndSupplier(preListId);
    }
}
