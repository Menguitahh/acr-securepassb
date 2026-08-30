package com.securepass.modules.invitation.infrastructure.persistence;

import com.securepass.modules.invitation.application.WorkerValidationQuery;
import com.securepass.shared.schedule.ScheduleWindow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WorkerValidationJdbcQuery implements WorkerValidationQuery {

    private final JdbcClient jdbc;

    public WorkerValidationJdbcQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<WorkerContext> loadContext(UUID userId, UUID neighborhoodId) {
        String workerName = jdbc.sql("SELECT full_name FROM users WHERE id = :uid")
                .param("uid", userId)
                .query(String.class)
                .optional()
                .orElse(null);
        if (workerName == null) return Optional.empty();

        String tz = jdbc.sql("SELECT timezone FROM neighborhoods WHERE id = :nid")
                .param("nid", neighborhoodId)
                .query(String.class)
                .optional()
                .orElse(null);
        if (tz == null) return Optional.empty();

        List<ActiveWorkerLot> lots = jdbc.sql("""
                SELECT p.lot_id,
                       l.code AS lot_code,
                       p.attributes->>'service'    AS service,
                       p.attributes->>'dailyFrom'  AS daily_from,
                       p.attributes->>'dailyUntil' AS daily_until,
                       (SELECT array_agg(value::int)
                          FROM jsonb_array_elements_text(p.attributes->'weekdays') AS value)
                                                   AS weekdays
                  FROM lot_permissions p
                  JOIN lots l ON l.id = p.lot_id
                 WHERE p.user_id = :uid
                   AND l.neighborhood_id = :nid
                   AND p.permission_type = 'WORKER'
                   AND p.active = TRUE
                """)
                .param("uid", userId)
                .param("nid", neighborhoodId)
                .query((rs, i) -> mapLot(rs))
                .list();

        return Optional.of(new WorkerContext(workerName, ZoneId.of(tz), lots));
    }

    private ActiveWorkerLot mapLot(ResultSet rs) throws SQLException {
        Array wdArr = rs.getArray("weekdays");
        List<Integer> weekdays = toIntList(wdArr);
        ScheduleWindow schedule = new ScheduleWindow(
                weekdays,
                LocalTime.parse(rs.getString("daily_from")),
                LocalTime.parse(rs.getString("daily_until")));
        return new ActiveWorkerLot(
                rs.getObject("lot_id", UUID.class),
                rs.getString("lot_code"),
                rs.getString("service"),
                schedule);
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
