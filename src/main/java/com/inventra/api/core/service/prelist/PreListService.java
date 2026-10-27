package com.inventra.api.core.service.prelist;

import java.util.List;

import org.springframework.stereotype.Service;

import com.inventra.api.core.domain.prelist.PreList;
import com.inventra.api.core.domain.prelist.PreListItem;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.PreListItemRepository;
import com.inventra.api.infrastructure.repository.PreListRepository;
import com.inventra.api.infrastructure.security.KitchenAccessGuard;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PreListService implements PreListUseCase {

    private final PreListRepository repository;
    private final PreListItemRepository itemRepository;
    private final KitchenAccessGuard accessGuard;

    @Override
    public PreList findById(Integer id) {
        PreList preList = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Pré-lista não encontrada."));
        // lista sem cozinha (importada antes da V018) continua visível para todos
        if (preList.getKitchen() != null) {
            accessGuard.assertAccess(preList.getKitchen().getId());
        }
        return preList;
    }

    @Override
    public List<PreList> list(Integer kitchenId) {
        // sem kitchenId, assume a cozinha do usuário logado; sem cozinha própria, não há o que listar
        Integer effectiveKitchenId = kitchenId != null ? kitchenId : accessGuard.currentKitchenId();
        if (effectiveKitchenId == null) {
            return List.of();
        }
        accessGuard.assertAccess(effectiveKitchenId);
        return repository.findByKitchenIdOrderByListDateDescIdDesc(effectiveKitchenId);
    }

    @Override
    public List<PreListItem> listItems(Integer preListId) {
        findById(preListId);
        return itemRepository.findByPreListIdWithProductAndSupplier(preListId);
    }
}
