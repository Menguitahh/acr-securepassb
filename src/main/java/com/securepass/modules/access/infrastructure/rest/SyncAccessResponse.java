package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.SyncAccessUseCase;

import java.util.List;
import java.util.UUID;

public record SyncAccessResponse(List<EventResult> results) {

    public record EventResult(
            UUID clientEventId,
            String result,
            String reason,
            UUID logId,
            UUID enrollmentId,
            boolean clockSkew) {}

    public static SyncAccessResponse from(SyncAccessUseCase.Report report) {
        List<EventResult> results = report.results().stream()
                .map(r -> new EventResult(
                        r.clientEventId(),
                        r.result().name(),
                        r.reason() != null ? r.reason().name() : null,
                        r.logId(),
                        r.enrollmentId(),
                        r.clockSkew()))
                .toList();
        return new SyncAccessResponse(results);
    }
}
