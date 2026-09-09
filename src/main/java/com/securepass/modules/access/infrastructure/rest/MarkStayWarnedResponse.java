package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.MarkStayWarnedUseCase;

import java.time.Instant;
import java.util.UUID;

public record MarkStayWarnedResponse(UUID stayId, Instant warnedAt) {

    public static MarkStayWarnedResponse from(MarkStayWarnedUseCase.Result r) {
        return new MarkStayWarnedResponse(r.stayId(), r.warnedAt());
    }
}
