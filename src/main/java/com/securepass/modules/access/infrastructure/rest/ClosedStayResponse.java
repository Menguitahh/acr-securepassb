package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.domain.Stay;

import java.time.Instant;
import java.util.UUID;

public record ClosedStayResponse(
        UUID id,
        UUID exitLogId,
        Instant exitedAt,
        boolean closedManually,
        UUID closedBy
) {
    public static ClosedStayResponse from(Stay s) {
        return new ClosedStayResponse(s.id(), s.exitLogId(), s.exitedAt(), s.closedManually(), s.closedBy());
    }
}
