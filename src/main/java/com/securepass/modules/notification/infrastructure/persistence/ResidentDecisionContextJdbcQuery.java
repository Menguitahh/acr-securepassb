package com.securepass.modules.notification.infrastructure.persistence;

import com.securepass.modules.notification.application.ResidentDecisionContextQuery;
import lombok.AllArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@AllArgsConstructor
@Repository
public class ResidentDecisionContextJdbcQuery implements ResidentDecisionContextQuery {

    private final JdbcClient jdbc;

    @Override
    public Optional<Context> find(UUID invitationId) {
        // `entry_log_id` es NOT NULL (V4:52); si la stay existe abierta, gate_id
        // siempre esta. `created_by` es NOT NULL en invitations; users.full_name
        // idem. La stay puede haberse cerrado entre commit y listener: en ese
        // caso el WHERE falla y devolvemos empty (skip en el listener).
        return jdbc.sql("""
                SELECT s.id           AS stay_id,
                       al.gate_id     AS gate_id,
                       s.subject_name AS subject_name,
                       u.full_name    AS resident_name,
                       s.expected_until AS expected_until
                  FROM stays s
                  JOIN access_logs al ON al.id = s.entry_log_id
                  JOIN invitations inv ON inv.id = s.invitation_id
                  JOIN users u ON u.id = inv.created_by
                 WHERE s.invitation_id = :inv
                   AND s.exited_at IS NULL
                 LIMIT 1
                """)
                .param("inv", invitationId)
                .query((rs, i) -> new Context(
                        rs.getObject("stay_id", UUID.class),
                        rs.getObject("gate_id", UUID.class),
                        rs.getString("subject_name"),
                        rs.getString("resident_name"),
                        rs.getTimestamp("expected_until").toInstant()))
                .optional();
    }
}
