package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.AdminMetricsQuery;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OverstaysResponse(List<Overstay> items) {

    public record Overstay(
            UUID stayId,
            String subjectName,
            String subjectDocument,
            String lotCode,
            Instant enteredAt,
            Instant expectedUntil,
            long minutesElapsed) {}

    public static OverstaysResponse from(List<AdminMetricsQuery.OverstaySummary> src) {
        List<Overstay> items = src.stream()
                .map(o -> new Overstay(
                        o.stayId(), o.subjectName(), o.subjectDocument(), o.lotCode(),
                        o.enteredAt(), o.expectedUntil(), o.minutesElapsed()))
                .toList();
        return new OverstaysResponse(items);
    }
}
