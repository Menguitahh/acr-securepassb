package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.ActiveWorkerPermissionRow;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkerPermissionSyncResponse(
        UUID userId,
        UUID jti,
        UUID lotId,
        String lotCode,
        String service,
        List<Integer> weekdays,
        String dailyFrom,
        String dailyUntil,
        Instant updatedAt
) {
    public static WorkerPermissionSyncResponse from(ActiveWorkerPermissionRow row) {
        return new WorkerPermissionSyncResponse(
                row.userId(), row.jti(), row.lotId(), row.lotCode(), row.service(),
                row.schedule().weekdays(),
                row.schedule().dailyFrom().toString(),
                row.schedule().dailyUntil().toString(),
                row.updatedAt());
    }
}
