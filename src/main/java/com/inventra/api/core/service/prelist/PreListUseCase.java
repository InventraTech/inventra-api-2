package com.inventra.api.core.service.prelist;

import java.util.List;

import com.inventra.api.core.domain.prelist.PreList;
import com.inventra.api.core.domain.prelist.PreListItem;

public interface PreListUseCase {

    PreList findById(Integer id);

    // da mais recente para a mais antiga
    List<PreList> list();

    List<PreListItem> listItems(Integer preListId);
}
