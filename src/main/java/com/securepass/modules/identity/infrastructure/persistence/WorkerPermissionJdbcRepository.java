package com.securepass.modules.identity.infrastructure.persistence;

import com.securepass.modules.identity.application.WorkerListingRow;
import com.securepass.modules.identity.application.WorkerPermissionRepository;
import com.securepass.shared.schedule.ScheduleWindow;
import com.securepass.shared.security.WorkerPermission;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads y writes de {@code lot_permissions} filtrados por
 * {@code permission_type = 'WORKER'}. attributes JSONB tiene shape:
 * {@code {service, weekdays, dailyFrom, dailyUntil}}. La construccion de
 * la franja pasa por {@link ScheduleWindow} para reusar sus invariantes.
 */
@Repository
public class WorkerPermissionJdbcRepository implements WorkerPermissionRepository {

    private final JdbcClient jdbc;

    public WorkerPermissionJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<WorkerPermission> find(UUID userId, UUID lotId) {
        return jdbc.sql("""
                SELECT user_id, lot_id,
                       attributes->>'service'    AS service,
                       attributes->>'dailyFrom'  AS daily_from,
                       attributes->>'dailyUntil' AS daily_until,
                       (SELECT array_agg(value::int)
                          FROM jsonb_array_elements_text(attributes->'weekdays') AS value)
                                                 AS weekdays,
                       active
                  FROM lot_permissions
                 WHERE user_id = :uid AND lot_id = :lid
                   AND permission_type = 'WORKER'
                """)
                .param("uid", userId)
                .param("lid", lotId)
                .query(this::mapPermission)
                .optional();
    }

    @Override
    public List<WorkerListingRow> listActiveByLot(UUID lotId) {
        return jdbc.sql("""
                SELECT p.user_id,
                       u.full_name,
                       u.email,
                       u.document_number,
                       p.attributes->>'service'    AS service,
                       p.attributes->>'dailyFrom'  AS daily_from,
                       p.attributes->>'dailyUntil' AS daily_until,
                       (SELECT array_agg(value::int)
                          FROM jsonb_array_elements_text(p.attributes->'weekdays') AS value)
                                                   AS weekdays,
                       p.active
                  FROM lot_permissions p
                  JOIN users u ON u.id = p.user_id
                 WHERE p.lot_id = :lid
                   AND p.permission_type = 'WORKER'
                   AND p.active = TRUE
                 ORDER BY u.full_name ASC
                """)
                .param("lid", lotId)
                .query((rs, i) -> new WorkerListingRow(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getString("document_number"),
                        rs.getString("service"),
                        readSchedule(rs),
                        rs.getBoolean("active")))
                .list();
    }

    @Override
    public void save(UUID userId, UUID lotId, String service, ScheduleWindow schedule) {
        jdbc.sql("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (:uid, :lid, 'WORKER',
                        jsonb_build_object(
                            'service',    :service::text,
                            'weekdays',   :weekdays::jsonb,
                            'dailyFrom',  :dailyFrom::text,
                            'dailyUntil', :dailyUntil::text),
                        TRUE)
                """)
                .param("uid", userId)
                .param("lid", lotId)
                .param("service", service)
                .param("weekdays", weekdaysJson(schedule.weekdays()))
                .param("dailyFrom", schedule.dailyFrom().toString())
                .param("dailyUntil", schedule.dailyUntil().toString())
                .update();
    }

    @Override
    public void reactivate(UUID userId, UUID lotId, String service, ScheduleWindow schedule) {
        jdbc.sql("""
                UPDATE lot_permissions
                   SET attributes = jsonb_build_object(
                                        'service',    :service::text,
                                        'weekdays',   :weekdays::jsonb,
                                        'dailyFrom',  :dailyFrom::text,
                                        'dailyUntil', :dailyUntil::text),
                       active = TRUE,
                       updated_at = now()
                 WHERE user_id = :uid AND lot_id = :lid
                   AND permission_type = 'WORKER'
                """)
                .param("uid", userId)
                .param("lid", lotId)
                .param("service", service)
                .param("weekdays", weekdaysJson(schedule.weekdays()))
                .param("dailyFrom", schedule.dailyFrom().toString())
                .param("dailyUntil", schedule.dailyUntil().toString())
                .update();
    }

    @Override
    public int updateAttributes(UUID userId, UUID lotId, String service, ScheduleWindow schedule) {
        return jdbc.sql("""
                UPDATE lot_permissions
                   SET attributes = jsonb_build_object(
                                        'service',    :service::text,
                                        'weekdays',   :weekdays::jsonb,
                                        'dailyFrom',  :dailyFrom::text,
                                        'dailyUntil', :dailyUntil::text),
                       updated_at = now()
                 WHERE user_id = :uid AND lot_id = :lid
                   AND permission_type = 'WORKER'
                """)
                .param("uid", userId)
                .param("lid", lotId)
                .param("service", service)
                .param("weekdays", weekdaysJson(schedule.weekdays()))
                .param("dailyFrom", schedule.dailyFrom().toString())
                .param("dailyUntil", schedule.dailyUntil().toString())
                .update();
    }

    @Override
    public int countActiveByUser(UUID userId) {
        Integer c = jdbc.sql("""
                SELECT COUNT(*)::int
                  FROM lot_permissions
                 WHERE user_id = :uid
                   AND permission_type = 'WORKER'
                   AND active = TRUE
                """)
                .param("uid", userId)
                .query(Integer.class)
                .optional()
                .orElse(0);
        return c == null ? 0 : c;
    }

    @Override
    public int deactivate(UUID userId, UUID lotId) {
        return jdbc.sql("""
                UPDATE lot_permissions
                   SET active = FALSE, updated_at = now()
                 WHERE user_id = :uid AND lot_id = :lid
                   AND permission_type = 'WORKER'
                """)
                .param("uid", userId)
                .param("lid", lotId)
                .update();
    }

    private WorkerPermission mapPermission(ResultSet rs, int rowNum) throws SQLException {
        UUID userId = rs.getObject("user_id", UUID.class);
        UUID lotId = rs.getObject("lot_id", UUID.class);
        return new WorkerPermission(
                userId, lotId,
                rs.getString("service"),
                readSchedule(rs),
                rs.getBoolean("active"));
    }

    private ScheduleWindow readSchedule(ResultSet rs) throws SQLException {
        Array wdArr = rs.getArray("weekdays");
        List<Integer> weekdays = toIntList(wdArr);
        return new ScheduleWindow(
                weekdays,
                LocalTime.parse(rs.getString("daily_from")),
                LocalTime.parse(rs.getString("daily_until")));
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

    private static String weekdaysJson(List<Integer> weekdays) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < weekdays.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(weekdays.get(i));
        }
        sb.append(']');
        return sb.toString();
    }
}
