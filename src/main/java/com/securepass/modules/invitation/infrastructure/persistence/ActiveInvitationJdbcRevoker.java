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
    public int revokeActiveByLotAndUser(UUID lotId, UUID userId, UUID revokedBy) {
        // LOAD-BEARING: no agregar "AND resident_pass = FALSE".
        //
        // WBS-112: la baja de un familiar tiene que llevarse tambien su
        // resident_pass, porque es el QR permanente que ya esta en su
        // telefono. Si lo excluimos aca, el pase sobrevive, el jti NO
        // aparece en GET /access/revoked, el revoked_cache del guardia
        // no lo conoce, y la persona sigue entrando hasta que caduque
        // en 2099. Coincide en criterio con las invitaciones porque el
        // resident_pass se persiste como una fila mas en `invitations`
        // con created_by = el propio user (ver IssueResidentPassesUseCase),
        // status='ACTIVE' y resident_pass=TRUE. El filtro por created_by
        // ya lo captura. Test que blinda esto:
        //   FamilyMemberRevokeSyncHttpTest.bajaDeFamiliar_publicaJtiDelResidentPassEnAccessRevoked
        return jdbc.sql("""
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
