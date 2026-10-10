package com.securepass.modules.identity.application;

import com.securepass.shared.schedule.ScheduleWindow;

import java.util.UUID;

/**
 * Fila del listado de trabajadores de un lote: permiso WORKER + datos del
 * user asociado, resueltos en un solo query. Se expone al controller que la
 * mapea al DTO de respuesta.
 */
public record WorkerListingRow(
        UUID userId,
        String fullName,
        String email,
        String documentNumber,
        String service,
        ScheduleWindow schedule,
        boolean active,
        // US-54: PENDING_ACTIVATION muestra "Reenviar activacion".
        com.securepass.modules.identity.domain.UserStatus userStatus
) {}
