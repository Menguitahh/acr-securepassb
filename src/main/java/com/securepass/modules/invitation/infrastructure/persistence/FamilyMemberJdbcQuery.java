package com.securepass.modules.invitation.infrastructure.persistence;

import com.securepass.modules.invitation.application.FamilyMemberQuery;
import com.securepass.modules.invitation.application.FamilyMembership;
import lombok.AllArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@AllArgsConstructor
@Repository
public class FamilyMemberJdbcQuery implements FamilyMemberQuery {

    private final JdbcClient jdbc;

    @Override
    public Optional<FamilyMembership> find(UUID userId, UUID lotId) {
        // WBS-108 PR1: reads migrados a lot_permissions.
        return jdbc.sql("""
                SELECT user_id, lot_id,
                       (permission_type = 'OWNER')          AS is_owner,
                       (attributes->>'can_invite')::boolean AS can_invite,
                       active
                  FROM lot_permissions
                 WHERE user_id = :uid AND lot_id = :lid
                   AND permission_type IN ('OWNER','MEMBER')
                 ORDER BY CASE permission_type WHEN 'OWNER' THEN 0 ELSE 1 END
                 LIMIT 1
                """)
                .param("uid", userId)
                .param("lid", lotId)
                .query((rs, i) -> new FamilyMembership(
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("lot_id", UUID.class),
                        rs.getBoolean("is_owner"),
                        rs.getBoolean("can_invite"),
                        rs.getBoolean("active")))
                .optional();
    }
}
