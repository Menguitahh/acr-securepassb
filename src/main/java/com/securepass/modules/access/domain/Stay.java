package com.securepass.modules.access.domain;

import java.time.Instant;
import java.util.UUID;

public record Stay(
        UUID id,
        UUID neighborhoodId,
        UUID invitationId,
        UUID lotId,
        String subjectName,
        String subjectDocument,
        int companions,
        UUID entryLogId,
        UUID exitLogId,
        Instant enteredAt,
        Instant exitedAt,
        Instant expectedUntil,
        boolean closedManually,
        UUID closedBy
) {
    public Stay {
        if (id == null) throw new IllegalArgumentException("id requerido");
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        if (subjectName == null || subjectName.isBlank()) throw new IllegalArgumentException("subjectName requerido");
        if (companions < 0) throw new IllegalArgumentException("companions >= 0");
        if (entryLogId == null) throw new IllegalArgumentException("entryLogId requerido");
        if (enteredAt == null) throw new IllegalArgumentException("enteredAt requerido");
        if (expectedUntil == null) throw new IllegalArgumentException("expectedUntil requerido");
        if (exitedAt != null && exitedAt.isBefore(enteredAt)) {
            throw new IllegalArgumentException("exitedAt no puede ser previo a enteredAt");
        }
    }

    public boolean isOpen() {
        return exitedAt == null;
    }

    public Stay close(UUID exitLog, Instant at, boolean manually, UUID by) {
        if (!isOpen()) throw new IllegalStateException("stay ya cerrada");
        if (exitLog == null) throw new IllegalArgumentException("exitLogId requerido");
        if (at == null) throw new IllegalArgumentException("at requerido");
        if (at.isBefore(enteredAt)) throw new IllegalArgumentException("at no puede ser previo a enteredAt");
        return new Stay(id, neighborhoodId, invitationId, lotId,
                subjectName, subjectDocument, companions,
                entryLogId, exitLog, enteredAt, at, expectedUntil, manually, by);
    }
}
