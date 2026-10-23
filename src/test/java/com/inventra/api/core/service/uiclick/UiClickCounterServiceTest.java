package com.inventra.api.core.service.uiclick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.inventra.api.core.service.uiclick.UiClickCounterUseCase.UiClickBatchResult;
import com.inventra.api.infrastructure.repository.UiClickCounterRepository;

@ExtendWith(MockitoExtension.class)
class UiClickCounterServiceTest {

    @Mock
    private UiClickCounterRepository repository;

    @InjectMocks
    private UiClickCounterService service;

    @BeforeEach
    void setUp() {
        when(repository.findCounterColumns()).thenReturn(Set.of("bt_wb_login_sign_in", "nav_mb_global_stock"));
    }

    @Test
    void recordIncrementsOnlyKnownColumnsAndReportsTheRest() {
        UiClickBatchResult result = service.record(Map.of(
                "bt_wb_login_sign_in", 2L,
                "nav_mb_global_stock", 5L,
                "bt_wb_unknown", 1L,
                "x = x + 1; DROP TABLE tb_user; --", 1L));

        verify(repository).increment(new TreeMap<>(Map.of("bt_wb_login_sign_in", 2L, "nav_mb_global_stock", 5L)));
        assertEquals(2, result.recordedElements());
        assertEquals(7L, result.recordedClicks());
        assertEquals(List.of("bt_wb_unknown", "x = x + 1; DROP TABLE tb_user; --"), result.ignored());
    }

    @Test
    void recordSkipsTheUpdateWhenNoColumnIsKnown() {
        UiClickBatchResult result = service.record(Map.of("bt_wb_unknown", 3L));

        verify(repository, never()).increment(any());
        assertEquals(0, result.recordedElements());
        assertEquals(0L, result.recordedClicks());
        assertEquals(List.of("bt_wb_unknown"), result.ignored());
    }

    @Test
    void columnsAreReadFromTheDatabaseOnlyOnce() {
        service.record(Map.of("bt_wb_login_sign_in", 1L));
        service.record(Map.of("nav_mb_global_stock", 1L));

        verify(repository, times(1)).findCounterColumns();
    }
}
