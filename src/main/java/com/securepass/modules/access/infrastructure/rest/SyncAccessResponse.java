package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.SyncAccessUseCase;
import com.securepass.modules.access.domain.DenialReason;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

public record SyncAccessResponse(List<EventResult> results) {

    public record EventResult(
            UUID clientEventId,
            @Schema(implementation = SyncAccessUseCase.Outcome.class) String result,
            @Schema(implementation = DenialReason.class) String reason,
            UUID logId,
            UUID enrollmentId,
            boolean clockSkew,
            @Schema(implementation = SyncAccessUseCase.Rejection.class) String rejection) {}

    public static SyncAccessResponse from(SyncAccessUseCase.Report report) {
        List<EventResult> results = report.results().stream()
                .map(r -> new EventResult(
                        r.clientEventId(),
                        r.result().name(),
                        r.reason() != null ? r.reason().name() : null,
                        r.logId(),
                        r.enrollmentId(),
                        r.clockSkew(),
                        r.rejection() != null ? r.rejection().name() : null))
                .toList();
        return new SyncAccessResponse(results);
    }
}
