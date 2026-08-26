package com.securepass.modules.access.domain;

import java.time.Instant;
import java.util.UUID;

public record GuestEnrollment(
        UUID id,
        UUID invitationId,
        String documentNumber,
        Instant enrolledAt,
        UUID enrolledBy,
        UUID gateId
) {
    public GuestEnrollment {
        if (id == null) throw new IllegalArgumentException("id requerido");
        if (invitationId == null) throw new IllegalArgumentException("invitationId requerido");
        if (documentNumber == null || documentNumber.isBlank()) {
            throw new IllegalArgumentException("documentNumber requerido");
        }
        if (enrolledAt == null) throw new IllegalArgumentException("enrolledAt requerido");
    }
}
