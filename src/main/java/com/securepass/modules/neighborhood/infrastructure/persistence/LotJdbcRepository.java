package com.securepass.modules.neighborhood.infrastructure.persistence;

import com.securepass.modules.neighborhood.application.LotRepository;
import com.securepass.modules.neighborhood.domain.Lot;
import com.securepass.modules.neighborhood.domain.LotStatus;
import lombok.AllArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@AllArgsConstructor
@Repository
public class LotJdbcRepository implements LotRepository {

    private final JdbcClient jdbc;

    @Override
    public Optional<Lot> findById(UUID id) {
        return jdbc.sql("SELECT id, neighborhood_id, code, street, number, status, created_at, updated_at FROM lots WHERE id = :id")
                .param("id", id).query(mapper()).optional();
    }

    @Override
    public Optional<Lot> findByNeighborhoodAndCode(UUID neighborhoodId, String code) {
        return jdbc.sql("SELECT id, neighborhood_id, code, street, number, status, created_at, updated_at FROM lots WHERE neighborhood_id = :n AND code = :c")
                .param("n", neighborhoodId).param("c", code).query(mapper()).optional();
    }

    @Override
    public List<Lot> listByNeighborhood(UUID neighborhoodId, int page, int size) {
        return jdbc.sql("""
                SELECT id, neighborhood_id, code, street, number, status, created_at, updated_at
                  FROM lots
                 WHERE neighborhood_id = :n
                 ORDER BY code
                 LIMIT :size OFFSET :offset
                """)
                .param("n", neighborhoodId).param("size", size).param("offset", (long) page * size)
                .query(mapper()).list();
    }

    @Override
    public List<Lot> searchActiveByCodePrefix(UUID neighborhoodId, String prefix, int limit) {
        return jdbc.sql("""
                SELECT id, neighborhood_id, code, street, number, status, created_at, updated_at
                  FROM lots
                 WHERE neighborhood_id = :n
                   AND status = 'ACTIVE'
                   AND code ILIKE :prefix
                 ORDER BY code
                 LIMIT :limit
                """)
                .param("n", neighborhoodId)
                .param("prefix", prefix + "%")
                .param("limit", limit)
                .query(mapper()).list();
    }

    @Override
    public void save(Lot lot) {
        jdbc.sql("""
                INSERT INTO lots (id, neighborhood_id, code, street, number, status, created_at, updated_at)
                VALUES (:id, :n, :code, :street, :number, :status, :created, :updated)
                """)
                .param("id", lot.id())
                .param("n", lot.neighborhoodId())
                .param("code", lot.code())
                .param("street", lot.street())
                .param("number", lot.number())
                .param("status", lot.status().name())
                .param("created", java.sql.Timestamp.from(lot.createdAt()))
                .param("updated", java.sql.Timestamp.from(lot.updatedAt()))
                .update();
    }

    @Override
    public void update(Lot lot) {
        jdbc.sql("""
                UPDATE lots
                   SET street = :street, number = :number, status = :status, updated_at = :updated
                 WHERE id = :id
                """)
                .param("id", lot.id())
                .param("street", lot.street())
                .param("number", lot.number())
                .param("status", lot.status().name())
                .param("updated", java.sql.Timestamp.from(lot.updatedAt()))
                .update();
    }

    private RowMapper<Lot> mapper() {
        return (rs, i) -> new Lot(
                rs.getObject("id", UUID.class),
                rs.getObject("neighborhood_id", UUID.class),
                rs.getString("code"),
                rs.getString("street"),
                rs.getString("number"),
                LotStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
