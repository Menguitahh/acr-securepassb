package com.securepass.modules.access.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WBS-47 / WBS-48 / WBS-50: consultas de reporte para el dashboard admin.
 * Puerto de lectura sin logica de dominio — el impl JDBC arma los queries
 * directamente. Ver comentario en cada metodo para el shape exacto.
 */
public interface AdminMetricsQuery {

    /**
     * WBS-47 / US-28. Ocupacion actual (stays abiertas), ingresos y egresos
     * ALLOWED del dia (en la tz del barrio) y total denegados con breakdown
     * por reason. El "dia" se computa en la timezone del barrio.
     *
     * <p>Si {@code date} es {@code null}, se usa la fecha actual EN LA TZ
     * DEL BARRIO (no UTC). El default esta aca y no en el controller para
     * que el controller no tenga que hacer un query previo por la tz.
     */
    OverviewMetrics overview(UUID neighborhoodId, LocalDate date);

    /**
     * WBS-48 / US-29. Buckets de hora con conteo de ingresos y egresos
     * ALLOWED. Rango [from, to] en UTC.
     *
     * <p>Buckets alineados a la hora en la timezone del barrio: si el barrio
     * es Mendoza (UTC-3), un scan a las 22:30 local cae en el bucket que
     * empieza a las 22:00 local (devuelto como Instant UTC = 01:00 UTC del
     * dia siguiente). Consistente con {@link #overview} — el resto del
     * sistema razona en tz del barrio, no en UTC.
     */
    List<TrafficBucket> traffic(UUID neighborhoodId, Instant from, Instant to);

    /**
     * WBS-50 / US-31 / BR-08. Stays abiertas donde el tiempo transcurrido
     * desde el ingreso supera {@code neighborhoods.stay_alert_minutes}.
     * Orden ascendente por {@code entered_at} — primero las mas viejas.
     */
    List<OverstaySummary> overstays(UUID neighborhoodId);

    record OverviewMetrics(
            LocalDate date,
            long currentOccupancy,
            long allowedIn,
            long allowedOut,
            long denied,
            Map<String, Long> denialsByReason) {}

    record TrafficBucket(Instant bucketStart, long in, long out) {}

    record OverstaySummary(
            UUID stayId,
            String subjectName,
            String subjectDocument,
            String lotCode,
            Instant enteredAt,
            Instant expectedUntil,
            long minutesElapsed) {}
}
