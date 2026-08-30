package com.securepass.modules.access.application;

import com.securepass.shared.schedule.ScheduleWindow;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ActiveWorkerPermissionsQuery {
    List<ActiveWorkerPermissionRow> listActive(UUID neighborhoodId, Instant since, int limit);
}
