package com.securepass.modules.access.infrastructure.persistence;

import com.securepass.modules.access.application.ActiveWorkerPermissionRow;
import com.securepass.modules.access.application.ActiveWorkerPermissionsQuery;
import com.securepass.shared.schedule.ScheduleWindow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class ActiveWorkerPermissionsJdbcQuery implements ActiveWorkerPermissionsQuery {

    private final JdbcClient jdbc;

    public ActiveWorkerPermissionsJdbcQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ActiveWorkerPermissionRow> listActive(UUID neighborhoodId, Instant since, int limit) {
        String sinceClause = since == null ? "" : " AND p.updated_at >= :since";
        var q = jdbc.sql("""
                SELECT p.user_id,
                       wt.jti,
                       p.lot_id,
                       l.code AS lot_code,
                       p.attributes->>'service'    AS service,
                       p.attributes->>'dailyFrom'  AS daily_from,
                       p.attributes->>'dailyUntil' AS daily_until,
                       (SELECT array_agg(value::int)
                          FROM jsonb_array_elements_text(p.attributes->'weekdays') AS value)
                                                   AS weekdays,
                       p.updated_at
                  FROM lot_permissions p
                  JOIN lots l ON l.id = p.lot_id
                  JOIN worker_tokens wt ON wt.user_id = p.user_id AND wt.revoked_at IS NULL
                 WHERE l.neighborhood_id = :nid
                   AND p.permission_type = 'WORKER'
                   AND p.active = TRUE
                """ + sinceClause + """
                 ORDER BY p.updated_at ASC
                 LIMIT :limit
                """)
                .param("nid", neighborhoodId)
                .param("limit", limit);
        if (since != null) q.param("since", Timestamp.from(since));
        return q.query((rs, i) -> {
                    Array wdArr = rs.getArray("weekdays");
                    List<Integer> weekdays = toIntList(wdArr);
                    ScheduleWindow schedule = new ScheduleWindow(
                            weekdays,
                            LocalTime.parse(rs.getString("daily_from")),
                            LocalTime.parse(rs.getString("daily_until")));
                    return new ActiveWorkerPermissionRow(
                            rs.getObject("user_id", UUID.class),
                            rs.getObject("jti", UUID.class),
                            rs.getObject("lot_id", UUID.class),
                            rs.getString("lot_code"),
                            rs.getString("service"),
                            schedule,
                            rs.getTimestamp("updated_at").toInstant());
                })
                .list();
    }

    private static List<Integer> toIntList(Array sqlArray) throws SQLException {
        if (sqlArray == null) return List.of();
        Object raw = sqlArray.getArray();
        if (!(raw instanceof Object[] arr)) return List.of();
        List<Integer> out = new ArrayList<>(arr.length);
        for (Object o : arr) {
            if (o instanceof Number n) out.add(n.intValue());
        }
        return out;
    }
}
