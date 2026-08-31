package com.securepass.modules.invitation.application;

import com.securepass.shared.schedule.ScheduleWindow;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WBS-117: consulta de lectura para validar un QR de trabajador. Fusiona en
 * un solo query todo lo que necesita {@code evaluateWorker}: nombre y
 * documento del user, timezone del barrio, y sus permisos WORKER activos con
 * lot code + service + ScheduleWindow.
 *
 * <p>WBS-122: {@code workerDocument} se agrega para que la verificacion de
 * identidad por persona pueda comparar el DNI escaneado contra el del user
 * detras del token, sin salir a otra query.
 */
public interface WorkerValidationQuery {

    Optional<WorkerContext> loadContext(UUID userId, UUID neighborhoodId);

    record WorkerContext(
            String workerName,
            String workerDocument,
            ZoneId neighborhoodTimezone,
            List<ActiveWorkerLot> lots) {}

    record ActiveWorkerLot(UUID lotId, String lotCode, String service, ScheduleWindow schedule) {}
}
