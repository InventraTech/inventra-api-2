package com.inventra.api.core.service.kitchen;

// Cozinha salva com localização incompleta (sem coordenadas, ou sem cidade/UF): o geocodificador completa depois.
public record KitchenLocationPendingEvent(Integer kitchenId) {
}
