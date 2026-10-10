package com.securepass.modules.access.infrastructure.persistence;

import com.securepass.modules.access.application.LotSnapshot;
import com.securepass.modules.access.application.LotStatusQuery;
import com.securepass.modules.access.domain.LotStatus;
import lombok.AllArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

@AllArgsConstructor
@Repository
public class LotJdbcQuery implements LotStatusQuery {

    private final JdbcClient jdbc;

    @Override
    public Optional<UUID> findNeighborhoodId(UUID lotId) {
        return jdbc.sql("SELECT neighborhood_id FROM lots WHERE id = :id")
                .param("id", lotId)
                .query(UUID.class)
                .optional();
    }

    @Override
    public Optional<LotSnapshot> find(UUID lotId) {
        return jdbc.sql("""
                SELECT l.id, l.code, l.status, n.timezone
                  FROM lots l
                  JOIN neighborhoods n ON n.id = l.neighborhood_id
                 WHERE l.id = :id
                """)
                .param("id", lotId)
                .query((rs, i) -> new LotSnapshot(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        LotStatus.valueOf(rs.getString("status")),
                        ZoneId.of(rs.getString("timezone"))))
                .optional();
    }
}
