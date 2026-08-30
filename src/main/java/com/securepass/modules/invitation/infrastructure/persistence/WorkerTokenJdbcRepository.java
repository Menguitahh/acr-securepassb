package com.securepass.modules.invitation.infrastructure.persistence;

import com.securepass.modules.invitation.application.RevokedEntry;
import com.securepass.modules.invitation.application.WorkerTokenRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WorkerTokenJdbcRepository implements WorkerTokenRepository {

    private final JdbcClient jdbc;

    public WorkerTokenJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<WorkerTokenRow> findActive(UUID userId) {
        return jdbc.sql("""
                SELECT user_id, neighborhood_id, jti, issued_at
                  FROM worker_tokens
                 WHERE user_id = :uid AND revoked_at IS NULL
                """)
                .param("uid", userId)
                .query((rs, i) -> new WorkerTokenRow(
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("neighborhood_id", UUID.class),
                        rs.getObject("jti", UUID.class),
                        rs.getTimestamp("issued_at").toInstant()))
                .optional();
    }

    @Override
    public void insertActive(UUID userId, UUID neighborhoodId, UUID jti) {
        jdbc.sql("""
                INSERT INTO worker_tokens (user_id, neighborhood_id, jti)
                VALUES (:uid, :nid, :jti)
                """)
                .param("uid", userId)
                .param("nid", neighborhoodId)
                .param("jti", jti)
                .update();
    }

    @Override
    public int revokeActive(UUID userId) {
        return jdbc.sql("""
                UPDATE worker_tokens
                   SET revoked_at = now()
                 WHERE user_id = :uid AND revoked_at IS NULL
                """)
                .param("uid", userId)
                .update();
    }

    @Override
    public List<RevokedEntry> listRevoked(UUID neighborhoodId, Instant since, int limit) {
        String sinceClause = since == null ? "" : " AND revoked_at > :since";
        var query = jdbc.sql("""
                SELECT jti, revoked_at
                  FROM worker_tokens
                 WHERE neighborhood_id = :nid
                   AND revoked_at IS NOT NULL
                """ + sinceClause + """
                 ORDER BY revoked_at ASC
                 LIMIT :limit
                """)
                .param("nid", neighborhoodId)
                .param("limit", limit);
        if (since != null) query.param("since", Timestamp.from(since));
        return query.query((rs, i) -> new RevokedEntry(
                        rs.getObject("jti", UUID.class),
                        rs.getTimestamp("revoked_at").toInstant()))
                .list();
    }
}
