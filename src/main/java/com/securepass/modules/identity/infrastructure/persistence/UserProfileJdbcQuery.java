package com.securepass.modules.identity.infrastructure.persistence;

import com.securepass.modules.identity.application.LotSummary;
import com.securepass.modules.identity.application.UserProfileQuery;
import com.securepass.modules.identity.application.UserProfileSummary;
import com.securepass.modules.identity.domain.Role;
import com.securepass.shared.schedule.ScheduleWindow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class UserProfileJdbcQuery implements UserProfileQuery {

    private final JdbcClient jdbc;

    public UserProfileJdbcQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserProfileSummary summarize(UUID userId) {
        List<MembershipRow> memberships = jdbc.sql("""
                SELECT m.neighborhood_id, m.role, n.timezone
                  FROM memberships m
                  JOIN neighborhoods n ON n.id = m.neighborhood_id
                 WHERE m.user_id = :uid AND m.active = TRUE
                 ORDER BY m.created_at ASC
                """)
                .param("uid", userId)
                .query((rs, i) -> new MembershipRow(
                        rs.getObject("neighborhood_id", UUID.class),
                        Role.valueOf(rs.getString("role")),
                        rs.getString("timezone")))
                .list();

        UUID neighborhoodId = memberships.isEmpty() ? null : memberships.get(0).neighborhoodId();
        String neighborhoodTimezone = memberships.isEmpty() ? null : memberships.get(0).timezone();
        List<Role> roles = memberships.stream().map(MembershipRow::role).distinct().toList();

        // Reads sobre lot_permissions (ver FamilyMemberJdbcRepository para el mapeo).
        // #179: WORKER entra con servicio y franja; el JWT lo deja afuera
        // (UserProfileSummary.residentLotIds).
        List<LotSummary> lots = jdbc.sql("""
                SELECT l.id, l.code, fm.permission_type,
                       (fm.attributes->>'can_invite')::boolean AS can_invite,
                       fm.attributes->>'service'                AS service,
                       fm.attributes->>'dailyFrom'              AS daily_from,
                       fm.attributes->>'dailyUntil'             AS daily_until,
                       (SELECT array_agg(value::int ORDER BY value::int)
                          FROM jsonb_array_elements_text(fm.attributes->'weekdays') AS value) AS weekdays
                  FROM lot_permissions fm
                  JOIN lots l ON l.id = fm.lot_id
                 WHERE fm.user_id = :uid AND fm.active = TRUE
                   AND fm.permission_type IN ('OWNER','MEMBER','WORKER')
                 ORDER BY l.code, fm.permission_type
                """)
                .param("uid", userId)
                .query((rs, i) -> lot(rs))
                .list();

        return new UserProfileSummary(neighborhoodId, neighborhoodTimezone, roles, lots);
    }

    private static LotSummary lot(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        String code = rs.getString("code");
        String type = rs.getString("permission_type");
        if (!"WORKER".equals(type)) {
            return LotSummary.resident(id, code, rs.getBoolean("can_invite"), "OWNER".equals(type));
        }
        List<Integer> weekdays = new ArrayList<>();
        Array arr = rs.getArray("weekdays");
        if (arr != null) {
            for (Object d : (Object[]) arr.getArray()) weekdays.add(((Number) d).intValue());
        }
        return LotSummary.worker(id, code, rs.getString("service"), new ScheduleWindow(
                weekdays,
                LocalTime.parse(rs.getString("daily_from")),
                LocalTime.parse(rs.getString("daily_until"))));
    }

    private record MembershipRow(UUID neighborhoodId, Role role, String timezone) {}
}
