package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.domain.AccessLog;

import java.time.Instant;
import java.util.UUID;

public record ManualAccessResponse(
        UUID logId,
        UUID lotId,
        String subjectName,
        String subjectDocument,
        Instant capturedAt
) {
    public static ManualAccessResponse from(AccessLog log) {
        return new ManualAccessResponse(
                log.id(), log.lotId(), log.subjectName(), log.subjectDocument(), log.capturedAt());
    }
}
