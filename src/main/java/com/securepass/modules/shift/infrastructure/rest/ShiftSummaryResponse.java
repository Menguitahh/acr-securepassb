package com.securepass.modules.shift.infrastructure.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.securepass.modules.shift.application.ShiftSummary;
import com.securepass.modules.shift.domain.Shift;

import java.time.Instant;
import java.util.UUID;

// Superset de ShiftResponse para GET /shifts (US-33). Tipo aparte para que
// open/close/current no expongan un accessCount que ahi no se calcula.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShiftSummaryResponse(
        UUID id,
        UUID neighborhoodId,
        UUID gateId,
        String gateName,
        UUID guardId,
        String guardFullName,
        Instant startedAt,
        Instant endedAt,
        String handoverNotes,
        long accessCount
) {
    public static ShiftSummaryResponse from(ShiftSummary summary) {
        Shift s = summary.shift();
        return new ShiftSummaryResponse(
                s.id(), s.neighborhoodId(), s.gateId(), summary.gateName(),
                s.guardId(), summary.guardFullName(),
                s.startedAt(), s.endedAt(), s.handoverNotes(), summary.accessCount());
    }
}
