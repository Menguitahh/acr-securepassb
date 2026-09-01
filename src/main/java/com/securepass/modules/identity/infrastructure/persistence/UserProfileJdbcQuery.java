package com.securepass.modules.identity.infrastructure.persistence;

import com.securepass.modules.identity.application.LotSummary;
import com.securepass.modules.identity.application.UserProfileQuery;
import com.securepass.modules.identity.application.UserProfileSummary;
import com.securepass.modules.identity.domain.Role;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

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
        List<LotSummary> lots = jdbc.sql("""
                SELECT l.id, l.code,
                       (fm.attributes->>'can_invite')::boolean AS can_invite,
                       (fm.permission_type = 'OWNER')          AS is_owner
                  FROM lot_permissions fm
                  JOIN lots l ON l.id = fm.lot_id
                 WHERE fm.user_id = :uid AND fm.active = TRUE
                   AND fm.permission_type IN ('OWNER','MEMBER')
                 ORDER BY l.code
                """)
                .param("uid", userId)
                .query((rs, i) -> new LotSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getBoolean("can_invite"),
                        rs.getBoolean("is_owner")))
                .list();

        return new UserProfileSummary(neighborhoodId, neighborhoodTimezone, roles, lots);
    }

    private record MembershipRow(UUID neighborhoodId, Role role, String timezone) {}
}
