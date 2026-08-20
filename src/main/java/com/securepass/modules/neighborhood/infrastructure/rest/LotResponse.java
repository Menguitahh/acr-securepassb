package com.securepass.modules.neighborhood.infrastructure.rest;

import com.securepass.modules.neighborhood.domain.Lot;
import com.securepass.modules.neighborhood.domain.LotStatus;

import java.time.Instant;
import java.util.UUID;

public record LotResponse(
        UUID id,
        UUID neighborhoodId,
        String code,
        String street,
        String number,
        LotStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public static LotResponse from(Lot lot) {
        return new LotResponse(
                lot.id(), lot.neighborhoodId(), lot.code(), lot.street(), lot.number(),
                lot.status(), lot.createdAt(), lot.updatedAt());
    }
}
