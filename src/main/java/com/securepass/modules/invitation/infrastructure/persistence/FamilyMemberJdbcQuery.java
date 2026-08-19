package com.securepass.modules.invitation.infrastructure.persistence;

import com.securepass.modules.invitation.application.FamilyMemberQuery;
import com.securepass.modules.invitation.application.FamilyMembership;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class FamilyMemberJdbcQuery implements FamilyMemberQuery {

    private final JdbcClient jdbc;

    public FamilyMemberJdbcQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<FamilyMembership> find(UUID userId, UUID lotId) {
        return jdbc.sql("""
                SELECT user_id, lot_id, is_owner, can_invite, active
                  FROM family_members
                 WHERE user_id = :uid AND lot_id = :lid
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
