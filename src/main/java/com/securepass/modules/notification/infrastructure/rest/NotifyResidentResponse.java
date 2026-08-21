package com.securepass.modules.notification.infrastructure.rest;

import com.securepass.modules.notification.application.NotifyResidentOfStayUseCase;

import java.time.Instant;
import java.util.UUID;

public record NotifyResidentResponse(
        UUID stayId,
        Instant notifiedAt,
        long minutesRemaining
) {
    public static NotifyResidentResponse from(NotifyResidentOfStayUseCase.Result r) {
        return new NotifyResidentResponse(r.stayId(), r.notifiedAt(), r.minutesRemaining());
    }
}
