package com.securepass.modules.access.domain;

import java.time.Instant;
import java.util.UUID;

public record AccessLog(
        UUID id,
        UUID neighborhoodId,
        UUID gateId,
        UUID guardId,
        UUID invitationId,
        UUID lotId,
        Direction direction,
        SubjectType subjectType,
        boolean allowed,
        DenialReason denialReason,
        String subjectName,
        String subjectDocument,
        int companions,
        UUID tokenJti,
        Instant capturedAt,
        boolean syncedOffline,
        boolean clockSkew,
        UUID clientEventId
) {
    public AccessLog {
        if (id == null) throw new IllegalArgumentException("id requerido");
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        if (gateId == null) throw new IllegalArgumentException("gateId requerido");
        if (direction == null) throw new IllegalArgumentException("direction requerido");
        if (subjectType == null) throw new IllegalArgumentException("subjectType requerido");
        if (allowed && denialReason != null) {
            throw new IllegalArgumentException("ALLOWED no puede tener denialReason");
        }
        if (!allowed && denialReason == null) {
            throw new IllegalArgumentException("DENIED requiere denialReason");
        }
        if (companions < 0) throw new IllegalArgumentException("companions >= 0");
        if (capturedAt == null) throw new IllegalArgumentException("capturedAt requerido");
        if (clientEventId == null) throw new IllegalArgumentException("clientEventId requerido");
    }
}
