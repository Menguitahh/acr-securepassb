package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.LotStatus;

import java.time.ZoneId;
import java.util.UUID;

public record LotSnapshot(UUID id, String code, LotStatus status, ZoneId neighborhoodTimezone) {

    public LotSnapshot {
        if (id == null) throw new IllegalArgumentException("id requerido");
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code requerido");
        if (status == null) throw new IllegalArgumentException("status requerido");
        if (neighborhoodTimezone == null) throw new IllegalArgumentException("neighborhoodTimezone requerido");
    }
}
