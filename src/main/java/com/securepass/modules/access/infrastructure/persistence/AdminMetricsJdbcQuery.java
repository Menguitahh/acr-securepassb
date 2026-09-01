package com.securepass.modules.access.infrastructure.persistence;

import com.securepass.modules.access.application.AdminMetricsQuery;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class AdminMetricsJdbcQuery implements AdminMetricsQuery {

    private final JdbcClient jdbc;

    public AdminMetricsJdbcQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OverviewMetrics overview(UUID neighborhoodId, LocalDate date) {
        ZoneId tz = neighborhoodTimezone(neighborhoodId);
        LocalDate day = date != null ? date : LocalDate.now(tz);
        ZonedDateTime dayStartZ = day.atStartOfDay(tz);
        Instant dayStart = dayStartZ.toInstant();
        Instant dayEnd = dayStartZ.plusDays(1).toInstant();

        long occupancy = jdbc.sql("""
                SELECT COUNT(*) FROM stays
                 WHERE neighborhood_id = :nid AND exited_at IS NULL
                """)
                .param("nid", neighborhoodId)
                .query(Long.class)
                .single();

        // Un solo scan sobre access_logs cubre in/out/denied.
        record DirRow(String direction, String result, String denialReason, long count) {}
        List<DirRow> rows = jdbc.sql("""
                SELECT direction, result, denial_reason, COUNT(*) AS c
                  FROM access_logs
                 WHERE neighborhood_id = :nid
                   AND captured_at >= :from AND captured_at < :to
                 GROUP BY direction, result, denial_reason
                """)
                .param("nid", neighborhoodId)
                .param("from", Timestamp.from(dayStart))
                .param("to", Timestamp.from(dayEnd))
                .query((rs, i) -> new DirRow(
                        rs.getString("direction"), rs.getString("result"),
                        rs.getString("denial_reason"), rs.getLong("c")))
                .list();

        long allowedIn = 0, allowedOut = 0, denied = 0;
        Map<String, Long> denials = new HashMap<>();
        for (DirRow r : rows) {
            if ("ALLOWED".equals(r.result())) {
                if ("IN".equals(r.direction())) allowedIn += r.count();
                else if ("OUT".equals(r.direction())) allowedOut += r.count();
            } else if ("DENIED".equals(r.result())) {
                denied += r.count();
                if (r.denialReason() != null) {
                    denials.merge(r.denialReason(), r.count(), Long::sum);
                }
            }
        }
        return new OverviewMetrics(day, occupancy, allowedIn, allowedOut, denied, denials);
    }

    @Override
    public List<TrafficBucket> traffic(UUID neighborhoodId, Instant from, Instant to) {
        // Buckets alineados a la hora local del barrio: date_trunc opera
        // sobre el timestamp convertido a la tz, y despues lo devolvemos
        // otra vez como TIMESTAMPTZ (UTC) para que el Instant que ve el
        // caller siga siendo un punto absoluto. Ver JavaDoc del port.
        String tz = jdbc.sql("SELECT timezone FROM neighborhoods WHERE id = :nid")
                .param("nid", neighborhoodId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("neighborhood " + neighborhoodId + " sin tz"));

        record BucketRow(Instant bucket, String direction, long count) {}
        List<BucketRow> rows = jdbc.sql("""
                SELECT (date_trunc('hour', captured_at AT TIME ZONE :tz) AT TIME ZONE :tz) AS bucket,
                       direction, COUNT(*) AS c
                  FROM access_logs
                 WHERE neighborhood_id = :nid
                   AND captured_at >= :from AND captured_at < :to
                   AND result = 'ALLOWED'
                 GROUP BY bucket, direction
                 ORDER BY bucket ASC
                """)
                .param("nid", neighborhoodId)
                .param("tz", tz)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rs, i) -> new BucketRow(
                        rs.getTimestamp("bucket").toInstant(),
                        rs.getString("direction"), rs.getLong("c")))
                .list();

        // Compacta filas por bucket manteniendo orden (Postgres ya ordeno por bucket).
        List<TrafficBucket> out = new ArrayList<>();
        Instant currentBucket = null;
        long inCount = 0, outCount = 0;
        for (BucketRow r : rows) {
            if (currentBucket == null || !currentBucket.equals(r.bucket())) {
                if (currentBucket != null) out.add(new TrafficBucket(currentBucket, inCount, outCount));
                currentBucket = r.bucket();
                inCount = 0; outCount = 0;
            }
            if ("IN".equals(r.direction())) inCount = r.count();
            else if ("OUT".equals(r.direction())) outCount = r.count();
        }
        if (currentBucket != null) out.add(new TrafficBucket(currentBucket, inCount, outCount));
        return out;
    }

    @Override
    public List<OverstaySummary> overstays(UUID neighborhoodId) {
        return jdbc.sql("""
                SELECT s.id, s.subject_name, s.subject_document,
                       s.entered_at, s.expected_until,
                       COALESCE(l.code, '') AS lot_code,
                       EXTRACT(EPOCH FROM (now() - s.entered_at)) / 60 AS mins
                  FROM stays s
                  JOIN neighborhoods n ON n.id = s.neighborhood_id
             LEFT JOIN lots l          ON l.id = s.lot_id
                 WHERE s.neighborhood_id = :nid
                   AND s.exited_at IS NULL
                   AND s.entered_at + (n.stay_alert_minutes * interval '1 minute') < now()
                 ORDER BY s.entered_at ASC
                """)
                .param("nid", neighborhoodId)
                .query((rs, i) -> new OverstaySummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("subject_name"),
                        rs.getString("subject_document"),
                        rs.getString("lot_code"),
                        rs.getTimestamp("entered_at").toInstant(),
                        rs.getTimestamp("expected_until").toInstant(),
                        (long) rs.getDouble("mins")))
                .list();
    }

    private ZoneId neighborhoodTimezone(UUID neighborhoodId) {
        String tz = jdbc.sql("SELECT timezone FROM neighborhoods WHERE id = :nid")
                .param("nid", neighborhoodId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("neighborhood " + neighborhoodId + " sin tz"));
        return ZoneId.of(tz);
    }
}
