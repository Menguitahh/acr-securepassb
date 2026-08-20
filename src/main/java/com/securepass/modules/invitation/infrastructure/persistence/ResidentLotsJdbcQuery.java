package com.securepass.modules.invitation.infrastructure.persistence;

import com.securepass.modules.invitation.application.ResidentLot;
import com.securepass.modules.invitation.application.ResidentLotsQuery;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class ResidentLotsJdbcQuery implements ResidentLotsQuery {

    private final JdbcClient jdbc;

    public ResidentLotsJdbcQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ResidentLot> listActiveByUser(UUID userId, UUID neighborhoodId) {
        return jdbc.sql("""
                SELECT l.id AS lot_id, l.code AS lot_code, n.id AS n_id, n.name AS n_name
                  FROM family_members fm
                  JOIN lots l ON l.id = fm.lot_id
                  JOIN neighborhoods n ON n.id = l.neighborhood_id
                 WHERE fm.user_id = :uid
                   AND fm.active = TRUE
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
