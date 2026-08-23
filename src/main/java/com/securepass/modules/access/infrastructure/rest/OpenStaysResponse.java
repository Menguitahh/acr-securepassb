package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.OpenStayView;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OpenStaysResponse(List<Item> stays) {

    public record Item(
            UUID id,
            UUID invitationId,
            String subjectName,
            String lotCode,
            Instant enteredAt,
            Instant expectedUntil,
            long minutesRemaining,
            boolean warned,
            Instant exitRequestedAt
    ) {}

    public static OpenStaysResponse from(List<OpenStayView> views, Instant now) {
        List<Item> items = views.stream()
                .map(v -> new Item(
                        v.id(),
                        v.invitationId(),
                        v.subjectName(),
                        v.lotCode(),
                        v.enteredAt(),
                        v.expectedUntil(),
                        Math.max(0, Duration.between(now, v.expectedUntil()).toMinutes()),
                        v.warned(),
                        v.exitRequestedAt()))
                .toList();
        return new OpenStaysResponse(items);
    }
}
