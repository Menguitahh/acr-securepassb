package com.securepass.modules.identity.infrastructure.persistence;

import com.securepass.modules.identity.application.MinorRepository;
import com.securepass.modules.identity.domain.Minor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MinorJdbcRepository implements MinorRepository {

    private static final String COLS =
            "id, lot_id, neighborhood_id, full_name, document_number, birth_date, " +
            "relation, notes, created_by, created_at";

    private final JdbcClient jdbc;

    public MinorJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> findLotNeighborhood(UUID lotId) {
        return jdbc.sql("SELECT neighborhood_id FROM lots WHERE id = :id")
                .param("id", lotId)
                .query(UUID.class)
                .optional();
    }

    @Override
    public void save(Minor minor) {
        jdbc.sql("""
                INSERT INTO lot_minors (
                    id, lot_id, neighborhood_id, full_name, document_number,
                    birth_date, relation, notes, created_by)
                VALUES (:id, :lot, :nid, :name, :doc, :birth, :relation, :notes, :by)
                """)
                .param("id", minor.id())
                .param("lot", minor.lotId())
                .param("nid", minor.neighborhoodId())
                .param("name", minor.fullName())
                .param("doc", minor.documentNumber())
                .param("birth", Date.valueOf(minor.birthDate()))
                .param("relation", minor.relation())
                .param("notes", minor.notes())
                .param("by", minor.createdBy())
                .update();
    }

    @Override
    public Optional<Minor> findById(UUID id) {
        return jdbc.sql("SELECT " + COLS + " FROM lot_minors WHERE id = :id")
                .param("id", id)
                .query(this::mapRow)
                .optional();
    }

    @Override
    public List<Minor> findByLot(UUID lotId) {
        return jdbc.sql("SELECT " + COLS + " FROM lot_minors WHERE lot_id = :lot ORDER BY created_at ASC")
                .param("lot", lotId)
                .query(this::mapRow)
                .list();
    }

    @Override
    public boolean delete(UUID id) {
        return jdbc.sql("DELETE FROM lot_minors WHERE id = :id")
                .param("id", id)
                .update() == 1;
    }

    private Minor mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Minor(
                rs.getObject("id", UUID.class),
                rs.getObject("lot_id", UUID.class),
                rs.getObject("neighborhood_id", UUID.class),
                rs.getString("full_name"),
                rs.getString("document_number"),
                rs.getDate("birth_date").toLocalDate(),
                rs.getString("relation"),
                rs.getString("notes"),
                rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant());
    }
}
