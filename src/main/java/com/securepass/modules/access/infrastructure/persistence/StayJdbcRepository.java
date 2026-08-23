package com.securepass.modules.access.infrastructure.persistence;

import com.securepass.modules.access.application.StayRepository;
import com.securepass.modules.access.domain.Stay;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
public class StayJdbcRepository implements StayRepository {

    private final JdbcClient jdbc;

    public StayJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Stay> findById(UUID id) {
        return jdbc.sql("""
                SELECT id, neighborhood_id, invitation_id, lot_id,
                       subject_name, subject_document, companions,
                       entry_log_id, exit_log_id, entered_at, exited_at,
                       expected_until, closed_manually, closed_by
                  FROM stays
                 WHERE id = :id
                """)
                .param("id", id)
                .query(this::mapRow)
                .optional();
    }

    private Stay mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Stay(
                rs.getObject("id", UUID.class),
                rs.getObject("neighborhood_id", UUID.class),
                rs.getObject("invitation_id", UUID.class),
                rs.getObject("lot_id", UUID.class),
                rs.getString("subject_name"),
                rs.getString("subject_document"),
                rs.getInt("companions"),
                rs.getObject("entry_log_id", UUID.class),
                rs.getObject("exit_log_id", UUID.class),
                rs.getTimestamp("entered_at").toInstant(),
                rs.getTimestamp("exited_at") == null ? null : rs.getTimestamp("exited_at").toInstant(),
                rs.getTimestamp("expected_until").toInstant(),
                rs.getBoolean("closed_manually"),
                rs.getObject("closed_by", UUID.class));
    }

    @Override
    public Optional<Stay> findOpenByInvitationId(UUID invitationId) {
        return jdbc.sql("""
                SELECT id, neighborhood_id, invitation_id, lot_id,
                       subject_name, subject_document, companions,
                       entry_log_id, exit_log_id, entered_at, exited_at,
                       expected_until, closed_manually, closed_by
                  FROM stays
                 WHERE invitation_id = :inv AND exited_at IS NULL
                 LIMIT 1
                """)
                .param("inv", invitationId)
                .query((rs, i) -> new Stay(
                        rs.getObject("id", UUID.class),
                        rs.getObject("neighborhood_id", UUID.class),
                        rs.getObject("invitation_id", UUID.class),
                        rs.getObject("lot_id", UUID.class),
                        rs.getString("subject_name"),
                        rs.getString("subject_document"),
                        rs.getInt("companions"),
                        rs.getObject("entry_log_id", UUID.class),
                        rs.getObject("exit_log_id", UUID.class),
                        rs.getTimestamp("entered_at").toInstant(),
                        rs.getTimestamp("exited_at") == null ? null : rs.getTimestamp("exited_at").toInstant(),
                        rs.getTimestamp("expected_until").toInstant(),
                        rs.getBoolean("closed_manually"),
                        rs.getObject("closed_by", UUID.class)))
                .optional();
    }

    @Override
    public Stay openIfNotOpen(Stay s) {
        try {
            jdbc.sql("""
                    INSERT INTO stays (
                        id, neighborhood_id, invitation_id, lot_id,
                        subject_name, subject_document, companions,
                        entry_log_id, entered_at, expected_until
                    ) VALUES (
                        :id, :nid, :inv, :lot,
                        :name, :doc, :comp,
                        :entry, :entered, :expected
                    )
                    """)
                    .param("id", s.id())
                    .param("nid", s.neighborhoodId())
                    .param("inv", s.invitationId())
                    .param("lot", s.lotId())
                    .param("name", s.subjectName())
                    .param("doc", s.subjectDocument())
                    .param("comp", s.companions())
                    .param("entry", s.entryLogId())
                    .param("entered", Timestamp.from(s.enteredAt()))
                    .param("expected", Timestamp.from(s.expectedUntil()))
                    .update();
            return s;
        } catch (DuplicateKeyException dup) {
            return findOpenByInvitationId(s.invitationId())
                    .orElseThrow(() -> new IllegalStateException("stay abierta perdida tras conflict", dup));
        }
    }

    @Override
    public java.util.List<com.securepass.modules.access.application.OpenStayView> listOpenByNeighborhood(UUID neighborhoodId) {
        return jdbc.sql("""
                SELECT s.id, s.subject_name, l.code AS lot_code,
                       s.entered_at, s.expected_until, s.warned_at,
                       s.exit_requested_at
                  FROM stays s
                  LEFT JOIN lots l ON l.id = s.lot_id
                 WHERE s.neighborhood_id = :nid
                   AND s.exited_at IS NULL
                 ORDER BY s.entered_at DESC
                """)
                .param("nid", neighborhoodId)
                .query((rs, i) -> new com.securepass.modules.access.application.OpenStayView(
                        rs.getObject("id", UUID.class),
                        rs.getString("subject_name"),
                        rs.getString("lot_code"),
                        rs.getTimestamp("entered_at").toInstant(),
                        rs.getTimestamp("expected_until").toInstant(),
                        rs.getTimestamp("warned_at") != null,
                        rs.getTimestamp("exit_requested_at") == null ? null : rs.getTimestamp("exit_requested_at").toInstant()))
                .list();
    }

    @Override
    public Optional<java.time.Instant> requestExitByInvitation(UUID invitationId, UUID requestedBy, java.time.Instant at) {
        // Solo pisa NULL: conserva la primera solicitud (idempotencia).
        jdbc.sql("""
                UPDATE stays
                   SET exit_requested_at = :at,
                       exit_requested_by = :by
                 WHERE invitation_id = :inv
                   AND exited_at IS NULL
                   AND exit_requested_at IS NULL
                """)
                .param("inv", invitationId)
                .param("by", requestedBy)
                .param("at", Timestamp.from(at))
                .update();
        return jdbc.sql("""
                SELECT exit_requested_at
                  FROM stays
                 WHERE invitation_id = :inv AND exited_at IS NULL
                 LIMIT 1
                """)
                .param("inv", invitationId)
                .query((rs, i) -> rs.getTimestamp("exit_requested_at"))
                .optional()
                .map(ts -> ts == null ? null : ts.toInstant());
    }

    @Override
    public void markResidentNotified(UUID stayId, java.time.Instant at) {
        jdbc.sql("UPDATE stays SET resident_notified_at = :at WHERE id = :id")
                .param("id", stayId)
                .param("at", Timestamp.from(at))
                .update();
    }

    @Override
    public void updateExpectedUntilByInvitation(UUID invitationId, java.time.Instant expectedUntil) {
        jdbc.sql("""
                UPDATE stays
                   SET expected_until = :expected
                 WHERE invitation_id = :inv AND exited_at IS NULL
                """)
                .param("inv", invitationId)
                .param("expected", Timestamp.from(expectedUntil))
                .update();
    }

    @Override
    public java.util.Optional<java.time.Instant> findResidentNotifiedAt(UUID stayId) {
        return jdbc.sql("SELECT resident_notified_at FROM stays WHERE id = :id")
                .param("id", stayId)
                .query((rs, i) -> rs.getTimestamp("resident_notified_at"))
                .optional()
                .map(ts -> ts == null ? null : ts.toInstant());
    }

    @Override
    public void close(Stay s) {
        jdbc.sql("""
                UPDATE stays
                   SET exit_log_id = :exit, exited_at = :at,
                       closed_manually = :manual, closed_by = :by
                 WHERE id = :id AND exited_at IS NULL
                """)
                .param("id", s.id())
                .param("exit", s.exitLogId())
                .param("at", Timestamp.from(s.exitedAt()))
                .param("manual", s.closedManually())
                .param("by", s.closedBy())
                .update();
    }
}
