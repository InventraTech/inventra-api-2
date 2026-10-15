package com.inventra.api.infrastructure.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.inventra.api.core.service.ranking.RankingEntry;
import com.inventra.api.core.service.ranking.RankingService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class RankingController {
    private final RankingService service;

    @GetMapping("/ranking")
    public List<RankingEntry> top(@RequestParam Integer kitchenId,
            @RequestParam(defaultValue = "10") int limit) {
        return service.top(kitchenId, limit);
    }
}
