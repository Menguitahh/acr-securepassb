package com.securepass.modules.invitation.infrastructure.persistence;

import com.securepass.modules.invitation.application.RevokedEntry;
import com.securepass.modules.invitation.application.RevokedTokensQuery;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class RevokedTokensJdbcQuery implements RevokedTokensQuery {

    private final JdbcClient jdbc;

    public RevokedTokensJdbcQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<RevokedEntry> listRevoked(UUID neighborhoodId, Instant since, int limit) {
        String sinceInv = since == null ? "" : " AND revoked_at > :since ";
        String sinceWt  = since == null ? "" : " AND revoked_at > :since ";
        var q = jdbc.sql("""
                SELECT jti, revoked_at
                  FROM (
                    SELECT jti, revoked_at
                      FROM invitations
                     WHERE neighborhood_id = :nid
                       AND status = 'REVOKED'
                       AND revoked_at IS NOT NULL
                    """ + sinceInv + """
                    UNION ALL
                    SELECT jti, revoked_at
                      FROM worker_tokens
                     WHERE neighborhood_id = :nid
                       AND revoked_at IS NOT NULL
                    """ + sinceWt + """
                  ) t
                 ORDER BY revoked_at ASC
                 LIMIT :limit
                """)
                .param("nid", neighborhoodId)
                .param("limit", limit);
        if (since != null) q.param("since", Timestamp.from(since));
        return q.query((rs, i) -> new RevokedEntry(
                        rs.getObject("jti", UUID.class),
                        rs.getTimestamp("revoked_at").toInstant()))
                .list();
    }
}
