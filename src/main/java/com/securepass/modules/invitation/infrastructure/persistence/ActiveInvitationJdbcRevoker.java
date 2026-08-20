package com.securepass.modules.invitation.infrastructure.persistence;

import com.securepass.modules.identity.application.ActiveInvitationRevoker;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class ActiveInvitationJdbcRevoker implements ActiveInvitationRevoker {

    private final JdbcClient jdbc;

    public ActiveInvitationJdbcRevoker(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void revokeActiveByLotAndUser(UUID lotId, UUID userId, UUID revokedBy) {
        jdbc.sql("""
                UPDATE invitations
                   SET status = 'REVOKED',
                       revoked_at = now(),
                       revoked_by = :by,
                       updated_at = now()
                 WHERE lot_id = :lot
                   AND created_by = :user
                   AND status = 'ACTIVE'
                """)
                .param("lot", lotId)
                .param("user", userId)
                .param("by", revokedBy)
                .update();
    }
}
