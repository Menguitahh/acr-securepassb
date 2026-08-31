package com.securepass.modules.access.domain;

import java.time.Instant;
import java.util.UUID;

public record GuestEnrollment(
        UUID id,
        UUID neighborhoodId,
        UUID invitationId,
        String documentNumber,
        Instant enrolledAt,
        UUID enrolledBy,
        UUID gateId
) {
    public GuestEnrollment {
        if (id == null) throw new IllegalArgumentException("id requerido");
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        // invitationId es opcional: NULL cuando el enrolamiento vino por el flujo
        // del trabajador (WBS-122). Para invitados se guarda como auditoria del
        // primer ingreso que verifico el DNI.
        if (documentNumber == null || documentNumber.isBlank()) {
            throw new IllegalArgumentException("documentNumber requerido");
        }
        if (enrolledAt == null) throw new IllegalArgumentException("enrolledAt requerido");
    }
}
