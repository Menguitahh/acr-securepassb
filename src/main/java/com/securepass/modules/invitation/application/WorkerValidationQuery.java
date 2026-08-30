package com.securepass.modules.invitation.application;

import com.securepass.shared.schedule.ScheduleWindow;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WBS-117: consulta de lectura para validar un QR de trabajador. Fusiona en
 * un solo query todo lo que necesita {@code evaluateWorker}: nombre del user,
 * timezone del barrio, y sus permisos WORKER activos con lot code + service +
 * ScheduleWindow.
 */
public interface WorkerValidationQuery {

    Optional<WorkerContext> loadContext(UUID userId, UUID neighborhoodId);

    record WorkerContext(String workerName, ZoneId neighborhoodTimezone, List<ActiveWorkerLot> lots) {}

    record ActiveWorkerLot(UUID lotId, String lotCode, String service, ScheduleWindow schedule) {}
}
