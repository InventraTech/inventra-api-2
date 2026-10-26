package com.inventra.api.infrastructure.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.inventra.api.core.domain.prelist.PreList;

public interface PreListRepository extends JpaRepository<PreList, Integer> {

    List<PreList> findAllByOrderByListDateDescIdDesc();

    List<PreList> findByKitchenIdOrderByListDateDescIdDesc(Integer kitchenId);

}
