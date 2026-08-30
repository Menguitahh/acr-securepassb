package com.securepass.modules.access.application;

import com.securepass.shared.schedule.ScheduleWindow;

import java.time.Instant;
import java.util.UUID;

/**
 * Fila del sync de permisos WORKER: todo lo que el scanner necesita para
 * evaluar validate offline. La tz del barrio viaja en cada fila (redundante
 * pero plana): "martes de 8 a 12" esta definido en la zona del barrio, tiene
 * que venir de la misma fuente que la franja. Tomarla del login desincroniza
 * si el barrio cambia de zona; tomarla del dispositivo es directamente
 * incorrecto.
 */
public record ActiveWorkerPermissionRow(
        UUID userId,
        UUID jti,
        UUID lotId,
        String lotCode,
        String service,
        ScheduleWindow schedule,
        String timezone,
        Instant updatedAt
) {}
