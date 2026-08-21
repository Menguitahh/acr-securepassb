package com.securepass.modules.access.infrastructure.persistence;

import com.securepass.modules.access.application.AccessLogRepository;
import com.securepass.modules.access.domain.AccessLog;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.Direction;
import com.securepass.modules.access.domain.SubjectType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AccessLogJdbcRepository implements AccessLogRepository {

    private final JdbcClient jdbc;

    public AccessLogJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AccessLog saveIdempotent(AccessLog log) {
        try {
            // BR-09/WBS-27: si el guardia tiene un turno abierto, el log queda
            // asociado a el via subquery. Sin turno abierto, shift_id = NULL.
            jdbc.sql("""
                    INSERT INTO access_logs (
                        id, neighborhood_id, gate_id, guard_id, shift_id, invitation_id, lot_id,
                        direction, subject_type, result, denial_reason,
                        subject_name, subject_document, companions,
                        token_jti, captured_at, synced_offline, clock_skew, client_event_id
                    ) VALUES (
                        :id, :nid, :gate, :guard,
                        (SELECT id FROM guard_shifts WHERE guard_id = :guard AND ended_at IS NULL LIMIT 1),
                        :inv, :lot,
                        :direction, :subject, :result, :reason,
                        :name, :doc, :comp,
                        :jti, :captured, :synced, :skew, :evt
                    )
                    """)
                    .param("id", log.id())
                    .param("nid", log.neighborhoodId())
                    .param("gate", log.gateId())
                    .param("guard", log.guardId())
                    .param("inv", log.invitationId())
                    .param("lot", log.lotId())
                    .param("direction", log.direction().name())
                    .param("subject", log.subjectType().name())
                    .param("result", log.allowed() ? "ALLOWED" : "DENIED")
                    .param("reason", log.denialReason() == null ? null : log.denialReason().name())
                    .param("name", log.subjectName())
                    .param("doc", log.subjectDocument())
                    .param("comp", log.companions())
                    .param("jti", log.tokenJti())
                    .param("captured", Timestamp.from(log.capturedAt()))
                    .param("synced", log.syncedOffline())
                    .param("skew", log.clockSkew())
                    .param("evt", log.clientEventId())
                    .update();
            return log;
        } catch (DuplicateKeyException e) {
            return findByClientEventId(log.clientEventId())
                    .orElseThrow(() -> new IllegalStateException(
                            "conflicto de client_event_id sin fila existente", e));
        }
    }

    @Override
    public Optional<AccessLog> findByClientEventId(UUID clientEventId) {
        return jdbc.sql("""
                SELECT id, neighborhood_id, gate_id, guard_id, invitation_id, lot_id,
                       direction, subject_type, result, denial_reason,
                       subject_name, subject_document, companions,
                       token_jti, captured_at, synced_offline, clock_skew, client_event_id
                  FROM access_logs
                 WHERE client_event_id = :evt
                """)
                .param("evt", clientEventId)
                .query(this::mapRow)
                .optional();
    }

    private AccessLog mapRow(ResultSet rs, int rowNum) throws SQLException {
        String reason = rs.getString("denial_reason");
        return new AccessLog(
                rs.getObject("id", UUID.class),
                rs.getObject("neighborhood_id", UUID.class),
                rs.getObject("gate_id", UUID.class),
                rs.getObject("guard_id", UUID.class),
                rs.getObject("invitation_id", UUID.class),
                rs.getObject("lot_id", UUID.class),
                Direction.valueOf(rs.getString("direction")),
                SubjectType.valueOf(rs.getString("subject_type")),
                "ALLOWED".equals(rs.getString("result")),
                reason == null ? null : DenialReason.valueOf(reason),
                rs.getString("subject_name"),
                rs.getString("subject_document"),
                rs.getInt("companions"),
                rs.getObject("token_jti", UUID.class),
                rs.getTimestamp("captured_at").toInstant(),
                rs.getBoolean("synced_offline"),
                rs.getBoolean("clock_skew"),
                rs.getObject("client_event_id", UUID.class));
    }
}
