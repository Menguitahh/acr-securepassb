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
                SELECT neighborhood_id, role FROM memberships
                 WHERE user_id = :uid AND active = TRUE
                 ORDER BY created_at ASC
                """)
                .param("uid", userId)
                .query((rs, i) -> new MembershipRow(
                        rs.getObject("neighborhood_id", UUID.class),
                        Role.valueOf(rs.getString("role"))))
                .list();

        UUID neighborhoodId = memberships.isEmpty() ? null : memberships.get(0).neighborhoodId();
        List<Role> roles = memberships.stream().map(MembershipRow::role).distinct().toList();

        List<LotSummary> lots = jdbc.sql("""
                SELECT l.id, l.code, fm.can_invite, fm.is_owner
                  FROM family_members fm
                  JOIN lots l ON l.id = fm.lot_id
                 WHERE fm.user_id = :uid AND fm.active = TRUE
                 ORDER BY l.code
                """)
                .param("uid", userId)
                .query((rs, i) -> new LotSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getBoolean("can_invite"),
                        rs.getBoolean("is_owner")))
                .list();

        return new UserProfileSummary(neighborhoodId, roles, lots);
    }

    private record MembershipRow(UUID neighborhoodId, Role role) {}
}
