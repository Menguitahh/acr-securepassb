package com.securepass.modules.shift.infrastructure.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.securepass.modules.shift.domain.Shift;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShiftResponse(
        UUID id,
        UUID neighborhoodId,
        UUID gateId,
        UUID guardId,
        Instant startedAt,
        Instant endedAt,
        String handoverNotes
) {
    public static ShiftResponse from(Shift s) {
        return new ShiftResponse(
                s.id(), s.neighborhoodId(), s.gateId(), s.guardId(),
                s.startedAt(), s.endedAt(), s.handoverNotes());
    }
}
