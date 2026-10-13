package com.inventra.api.infrastructure.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.inventra.api.core.domain.inventory.Inventory;
import com.inventra.api.core.domain.inventory.enums.InventoryStatus;

public interface InventoryRepository extends JpaRepository<Inventory, Integer> {

    List<Inventory> findByKitchenId(Integer kitchenId);

    boolean existsByKitchenIdAndStatus(Integer kitchenId, InventoryStatus status);

    // Procedure criada em V3__business_rules.sql
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "CALL sp_close_inventory(:inventoryId)", nativeQuery = true)
    void callCloseInventory(@Param("inventoryId") Integer inventoryId);

}
