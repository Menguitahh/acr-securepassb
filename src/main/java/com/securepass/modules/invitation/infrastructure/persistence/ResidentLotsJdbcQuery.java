package com.securepass.modules.invitation.infrastructure.persistence;

import com.securepass.modules.invitation.application.ResidentLot;
import com.securepass.modules.invitation.application.ResidentLotsQuery;
import lombok.AllArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@AllArgsConstructor
@Repository
public class ResidentLotsJdbcQuery implements ResidentLotsQuery {

    private final JdbcClient jdbc;

    @Override
    public List<ResidentLot> listActiveByUser(UUID userId, UUID neighborhoodId) {
        // WBS-108 PR1: reads migrados a lot_permissions.
        // DISTINCT porque un mismo user puede tener OWNER+MEMBER sobre el
        // mismo lote en teoria (aunque la migracion no lo produce hoy).
        return jdbc.sql("""
                SELECT DISTINCT l.id AS lot_id, l.code AS lot_code,
                                n.id AS n_id, n.name AS n_name
                  FROM lot_permissions fm
                  JOIN lots l ON l.id = fm.lot_id
                  JOIN neighborhoods n ON n.id = l.neighborhood_id
                 WHERE fm.user_id = :uid
                   AND fm.active = TRUE
                   AND fm.permission_type IN ('OWNER','MEMBER')
                   AND n.id = :nid
                   AND l.status = 'ACTIVE'
                 ORDER BY l.code
                """)
                .param("uid", userId)
                .param("nid", neighborhoodId)
                .query((rs, i) -> new ResidentLot(
                        rs.getObject("lot_id", UUID.class),
                        rs.getString("lot_code"),
                        rs.getObject("n_id", UUID.class),
                        rs.getString("n_name")))
                .list();
    }
}
