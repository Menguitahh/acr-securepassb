package com.securepass.modules.access.application;

import com.securepass.shared.schedule.ScheduleWindow;

import java.time.Instant;
import java.util.UUID;

/**
 * Fila del sync de permisos WORKER: todo lo que el scanner necesita para
 * evaluar validate offline (worker_token jti + user + lot + service + franja
 * + tz del barrio ya viene junto con la signing key).
 */
public record ActiveWorkerPermissionRow(
        UUID userId,
        UUID jti,
        UUID lotId,
        String lotCode,
        String service,
        ScheduleWindow schedule,
        Instant updatedAt
) {}
