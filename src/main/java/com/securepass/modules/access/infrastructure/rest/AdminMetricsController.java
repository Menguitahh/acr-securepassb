package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.AdminMetricsQuery;
import com.securepass.shared.exception.BusinessRuleViolationException;
import com.securepass.shared.security.AccessGuard;
import com.securepass.shared.security.CurrentUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * WBS-47 / WBS-48 / WBS-50: endpoints de metricas para el dashboard admin.
 *
 * <p>Aislamiento por barrio: el {@code neighborhoodId} se toma SIEMPRE del
 * JWT del actor via {@link AccessGuard}. No hay parametro por barrio. Un
 * admin del barrio A que quiera espiar el B no tiene forma de expresarlo
 * en la API. Cubierto por test.
 */
@RestController
@RequestMapping("/api/v1/admin/metrics")
@PreAuthorize("hasRole('ADMIN')")
public class AdminMetricsController {

    // WBS-48: tope del rango de traffic. Un rango de 90 dias con buckets
    // horarios devuelve hasta 2160 puntos, manejable en el chart. Rangos
    // mas largos disparan un scan pesado sin uso concreto — el dashboard
    // muestra dias/semanas, no anios.
    private static final Duration MAX_TRAFFIC_RANGE = Duration.ofDays(90);

    private final AdminMetricsQuery query;
    private final AccessGuard guard;

    public AdminMetricsController(AdminMetricsQuery query, AccessGuard guard) {
        this.query = query;
        this.guard = guard;
    }

    @GetMapping("/overview")
    public OverviewResponse overview(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        CurrentUser admin = guard.require();
        // El default "hoy en tz del barrio" lo resuelve la Query, que ya
        // consulta la tz para armar el rango. No re-consultamos aca.
        AdminMetricsQuery.OverviewMetrics m = query.overview(admin.neighborhoodId(), date);
        return OverviewResponse.from(date, m);
    }

    @GetMapping("/traffic")
    public TrafficResponse traffic(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        if (!to.isAfter(from)) {
            throw new BusinessRuleViolationException("traffic-range", "to debe ser posterior a from");
        }
        if (Duration.between(from, to).compareTo(MAX_TRAFFIC_RANGE) > 0) {
            throw new BusinessRuleViolationException("traffic-range",
                    "el rango excede el maximo de " + MAX_TRAFFIC_RANGE.toDays() + " dias");
        }
        CurrentUser admin = guard.require();
        List<AdminMetricsQuery.TrafficBucket> buckets = query.traffic(admin.neighborhoodId(), from, to);
        return TrafficResponse.from(from, to, buckets);
    }

    @GetMapping("/overstays")
    public OverstaysResponse overstays() {
        CurrentUser admin = guard.require();
        List<AdminMetricsQuery.OverstaySummary> items = query.overstays(admin.neighborhoodId());
        return OverstaysResponse.from(items);
    }
}
