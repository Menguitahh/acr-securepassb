package com.securepass.modules.shift.infrastructure.persistence;

import com.securepass.modules.shift.application.ShiftRepository;
import com.securepass.modules.shift.domain.Shift;
import com.securepass.shared.exception.StateConflictException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ShiftJdbcRepository implements ShiftRepository {

    private final JdbcClient jdbc;

    public ShiftJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Shift openIfNotOpen(Shift s) {
        try {
            jdbc.sql("""
                    INSERT INTO guard_shifts (id, neighborhood_id, gate_id, guard_id, started_at, handover_notes)
                    VALUES (:id, :nid, :gate, :guard, :started, :notes)
                    """)
                    .param("id", s.id())
                    .param("nid", s.neighborhoodId())
                    .param("gate", s.gateId())
                    .param("guard", s.guardId())
                    .param("started", Timestamp.from(s.startedAt()))
                    .param("notes", s.handoverNotes())
                    .update();
            return s;
        } catch (DuplicateKeyException dup) {
            // BR-09: uq_guard_open_shift bloquea dos turnos abiertos.
            throw new StateConflictException("el guardia ya tiene un turno abierto");
        }
    }

    @Override
    public Optional<Shift> findOpenByGuard(UUID guardId) {
        return jdbc.sql("""
                SELECT id, neighborhood_id, gate_id, guard_id, started_at, ended_at, handover_notes
                  FROM guard_shifts
                 WHERE guard_id = :guard AND ended_at IS NULL
                 LIMIT 1
                """)
                .param("guard", guardId).query(mapper()).optional();
    }

    @Override
    public Optional<Shift> findById(UUID id) {
        return jdbc.sql("""
                SELECT id, neighborhood_id, gate_id, guard_id, started_at, ended_at, handover_notes
                  FROM guard_shifts
                 WHERE id = :id
                """)
                .param("id", id).query(mapper()).optional();
    }

    @Override
    public void close(Shift s) {
        jdbc.sql("""
                UPDATE guard_shifts
                   SET ended_at = :ended, handover_notes = :notes
                 WHERE id = :id AND ended_at IS NULL
                """)
                .param("id", s.id())
                .param("ended", Timestamp.from(s.endedAt()))
                .param("notes", s.handoverNotes())
                .update();
    }

    @Override
    public List<Shift> listByFilter(UUID neighborhoodId, UUID gateId, Instant from, Instant to, int limit) {
        StringBuilder where = new StringBuilder("WHERE neighborhood_id = :nid");
        if (gateId != null) where.append(" AND gate_id = :gate");
        if (from != null) where.append(" AND started_at >= :from");
        if (to != null) where.append(" AND started_at <= :to");
        var query = jdbc.sql("""
                SELECT id, neighborhood_id, gate_id, guard_id, started_at, ended_at, handover_notes
                  FROM guard_shifts
                """ + where + """
                 ORDER BY started_at DESC
                 LIMIT :limit
                """)
                .param("nid", neighborhoodId)
                .param("limit", limit);
        if (gateId != null) query.param("gate", gateId);
        if (from != null) query.param("from", Timestamp.from(from));
        if (to != null) query.param("to", Timestamp.from(to));
        List<Shift> out = new ArrayList<>(query.query(mapper()).list());
        return out;
    }

    private RowMapper<Shift> mapper() {
        return (rs, i) -> new Shift(
                rs.getObject("id", UUID.class),
                rs.getObject("neighborhood_id", UUID.class),
                rs.getObject("gate_id", UUID.class),
                rs.getObject("guard_id", UUID.class),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("ended_at") == null ? null : rs.getTimestamp("ended_at").toInstant(),
                rs.getString("handover_notes"));
    }
}
