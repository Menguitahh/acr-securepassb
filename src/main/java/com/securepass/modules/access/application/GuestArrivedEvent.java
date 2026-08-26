package com.securepass.modules.access.application;

import java.time.Instant;
import java.util.UUID;

/**
 * BR-14 + US-25: se publica cuando `StayLifecycle` abre (o encuentra ya
 * abierta) una stay a partir de un `access_log` de ingreso ALLOWED. La
 * hora del evento es `enteredAt` real; para eventos sincronizados offline
 * eso es la hora original de ingreso, no la del sync (spec US-25).
 */
public record GuestArrivedEvent(
        UUID neighborhoodId,
        UUID stayId,
        UUID invitationId,
        UUID residentUserId,
        String subjectName,
        String lotCode,
        Instant enteredAt,
        boolean syncedOffline
) {
    public GuestArrivedEvent {
        if (stayId == null) throw new IllegalArgumentException("stayId requerido");
        if (invitationId == null) throw new IllegalArgumentException("invitationId requerido");
        if (residentUserId == null) throw new IllegalArgumentException("residentUserId requerido");
        if (subjectName == null || subjectName.isBlank()) throw new IllegalArgumentException("subjectName requerido");
        if (enteredAt == null) throw new IllegalArgumentException("enteredAt requerido");
    }
}
