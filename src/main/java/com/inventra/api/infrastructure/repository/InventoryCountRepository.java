package com.inventra.api.infrastructure.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.inventra.api.core.domain.inventory.InventoryCount;

public interface InventoryCountRepository extends JpaRepository<InventoryCount, Integer> {

    List<InventoryCount> findByInventoryId(Integer inventoryId);

    boolean existsByInventoryIdAndBatchId(Integer inventoryId, Integer batchId);

}
