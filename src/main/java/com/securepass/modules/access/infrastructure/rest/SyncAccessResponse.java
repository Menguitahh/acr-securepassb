package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.SyncAccessUseCase;

import java.util.List;
import java.util.UUID;

public record SyncAccessResponse(int accepted, List<Conflict> conflicts) {

    public record Conflict(UUID clientEventId, String serverResult, String reason, UUID logId, boolean clockSkew) {}

    public static SyncAccessResponse from(SyncAccessUseCase.Report report) {
        List<Conflict> conflicts = report.conflicts().stream()
                .map(c -> new Conflict(c.clientEventId(), "DENIED", c.reason().name(), c.logId(), c.clockSkew()))
                .toList();
        return new SyncAccessResponse(report.accepted(), conflicts);
    }
}
