package com.securepass.modules.access.application;

import com.securepass.shared.schedule.ScheduleWindow;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ActiveWorkerPermissionsQuery {
    List<ActiveWorkerPermissionRow> listActive(UUID neighborhoodId, Instant since, int limit);

    /**
     * #180: trabajadores del sync ({@link #listActive} sin {@code since}) con
     * enrolamiento en el barrio por su DNI, el mismo criterio que validate online.
     */
    List<UUID> enrolledWorkerIds(UUID neighborhoodId);
}
