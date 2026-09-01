package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.AdminMetricsQuery;

import java.time.LocalDate;
import java.util.Map;

public record OverviewResponse(
        LocalDate date,
        long currentOccupancy,
        long allowedIn,
        long allowedOut,
        long denied,
        Map<String, Long> denialsByReason
) {
    public static OverviewResponse from(LocalDate ignoredParam, AdminMetricsQuery.OverviewMetrics m) {
        // El date del response viene de la Query, que ya resolvio el default
        // en la tz del barrio. El param del controller queda para compat de
        // firma pero se ignora — la fuente de verdad es m.date().
        return new OverviewResponse(
                m.date(), m.currentOccupancy(), m.allowedIn(), m.allowedOut(),
                m.denied(), m.denialsByReason());
    }
}
