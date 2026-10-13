package com.inventra.api.core.service.alert;

import java.util.List;

import com.inventra.api.core.domain.alert.Alert;
import com.inventra.api.core.service.alert.model.request.CreateAlertRequest;

// Listagens e leitura são sempre do ponto de vista de quem está chamando: só os alertas que a pessoa
// enxerga (da cozinha, do perfil dela ou só dela) e o "lido" dela.
public interface AlertUseCase {

    Alert create(CreateAlertRequest request);

    // Estoquista avisa o supervisor que escaneou um produto fora do catálogo. Se já há um aviso aberto para
    // o mesmo código na cozinha, devolve o existente (created = false).
    UnregisteredProductReport reportUnregisteredProduct(Integer kitchenId, String barcode);

    AlertView findById(Integer id);

    List<AlertView> listByKitchen(Integer kitchenId);

    List<AlertView> listUnreadByKitchen(Integer kitchenId);

    // grava a leitura só para o usuário logado
    AlertView markAsRead(Integer id);

    // marca como lidos, para o usuário logado, todos os alertas não lidos que ele enxerga na cozinha
    void markAllAsRead(Integer kitchenId);

    void delete(Integer id);

    record UnregisteredProductReport(Alert alert, boolean created) {
    }
}
