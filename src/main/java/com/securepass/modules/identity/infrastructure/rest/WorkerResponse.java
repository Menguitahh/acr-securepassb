package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.WorkerListingRow;

import java.util.List;
import java.util.UUID;

public record WorkerResponse(
        UUID userId,
        String fullName,
        String email,
        String documentNumber,
        String service,
        List<Integer> weekdays,
        String dailyFrom,
        String dailyUntil,
        boolean active
) {
    public static WorkerResponse from(WorkerListingRow row) {
        return new WorkerResponse(
                row.userId(), row.fullName(), row.email(), row.documentNumber(),
                row.service(),
                row.schedule().weekdays(),
                row.schedule().dailyFrom().toString(),
                row.schedule().dailyUntil().toString(),
                row.active());
    }
}
