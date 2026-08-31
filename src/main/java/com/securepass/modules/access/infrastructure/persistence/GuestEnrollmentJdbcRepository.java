package com.securepass.modules.access.infrastructure.persistence;

import com.securepass.modules.access.application.GuestEnrollmentRepository;
import com.securepass.modules.access.domain.GuestEnrollment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
public class GuestEnrollmentJdbcRepository implements GuestEnrollmentRepository {

    private final JdbcClient jdbc;

    public GuestEnrollmentJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<GuestEnrollment> findByPerson(UUID neighborhoodId, String documentNumber) {
        return jdbc.sql("""
                SELECT id, neighborhood_id, invitation_id, document_number,
                       enrolled_at, enrolled_by, gate_id
                  FROM guest_enrollments
                 WHERE neighborhood_id = :nid AND document_number = :doc
                """)
                .param("nid", neighborhoodId)
                .param("doc", documentNumber)
                .query((rs, i) -> new GuestEnrollment(
                        rs.getObject("id", UUID.class),
                        rs.getObject("neighborhood_id", UUID.class),
                        rs.getObject("invitation_id", UUID.class),
                        rs.getString("document_number"),
                        rs.getTimestamp("enrolled_at").toInstant(),
                        rs.getObject("enrolled_by", UUID.class),
                        rs.getObject("gate_id", UUID.class)))
                .optional();
    }

    @Override
    public GuestEnrollment saveIfAbsent(GuestEnrollment e) {
        try {
            jdbc.sql("""
                    INSERT INTO guest_enrollments
                        (id, neighborhood_id, invitation_id, document_number,
                         enrolled_at, enrolled_by, gate_id)
                    VALUES (:id, :nid, :inv, :doc, :at, :by, :gate)
                    """)
                    .param("id", e.id())
                    .param("nid", e.neighborhoodId())
                    .param("inv", e.invitationId())
                    .param("doc", e.documentNumber())
                    .param("at", Timestamp.from(e.enrolledAt()))
                    .param("by", e.enrolledBy())
                    .param("gate", e.gateId())
                    .update();
            return e;
        } catch (DuplicateKeyException dup) {
            return findByPerson(e.neighborhoodId(), e.documentNumber())
                    .orElseThrow(() -> new IllegalStateException(
                            "conflicto de enrolamiento sin fila", dup));
        }
    }
}
