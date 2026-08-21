package com.securepass.modules.shift.domain;

import java.time.Instant;
import java.util.UUID;

public record Shift(
        UUID id,
        UUID neighborhoodId,
        UUID gateId,
        UUID guardId,
        Instant startedAt,
        Instant endedAt,
        String handoverNotes
) {
    private static final int NOTES_MAX = 1000;

    public Shift {
        if (id == null) throw new IllegalArgumentException("id requerido");
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        if (gateId == null) throw new IllegalArgumentException("gateId requerido");
        if (guardId == null) throw new IllegalArgumentException("guardId requerido");
        if (startedAt == null) throw new IllegalArgumentException("startedAt requerido");
        if (endedAt != null && !endedAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("endedAt debe ser posterior a startedAt");
        }
        if (handoverNotes != null && handoverNotes.length() > NOTES_MAX) {
            throw new IllegalArgumentException("handoverNotes excede " + NOTES_MAX);
        }
    }

    public boolean isOpen() {
        return endedAt == null;
    }

    public Shift close(Instant at, String notes) {
        if (!isOpen()) throw new IllegalStateException("turno ya cerrado");
        if (at == null) throw new IllegalArgumentException("at requerido");
        if (!at.isAfter(startedAt)) throw new IllegalArgumentException("at debe ser posterior a startedAt");
        return new Shift(id, neighborhoodId, gateId, guardId, startedAt, at,
                notes != null ? notes : handoverNotes);
    }
}
