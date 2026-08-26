package com.securepass.modules.neighborhood.domain;

import java.time.Instant;
import java.util.UUID;

public record Lot(
        UUID id,
        UUID neighborhoodId,
        String code,
        String street,
        String number,
        LotStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    private static final int CODE_MAX = 30;
    private static final int STREET_MAX = 120;
    private static final int NUMBER_MAX = 20;

    public Lot {
        if (id == null) throw new IllegalArgumentException("id requerido");
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code requerido");
        if (code.length() > CODE_MAX) throw new IllegalArgumentException("code excede " + CODE_MAX);
        if (street != null && street.length() > STREET_MAX) throw new IllegalArgumentException("street excede " + STREET_MAX);
        if (number != null && number.length() > NUMBER_MAX) throw new IllegalArgumentException("number excede " + NUMBER_MAX);
        if (status == null) throw new IllegalArgumentException("status requerido");
        if (createdAt == null) throw new IllegalArgumentException("createdAt requerido");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt requerido");
    }

    public Lot withStatus(LotStatus newStatus, Instant now) {
        if (newStatus == null) throw new IllegalArgumentException("status requerido");
        if (now == null) throw new IllegalArgumentException("now requerido");
        if (newStatus == status) return this;
        return new Lot(id, neighborhoodId, code, street, number, newStatus, createdAt, now);
    }

    public Lot withAddress(String newStreet, String newNumber, Instant now) {
        if (now == null) throw new IllegalArgumentException("now requerido");
        return new Lot(id, neighborhoodId, code, newStreet, newNumber, status, createdAt, now);
    }
}
