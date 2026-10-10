package com.securepass.modules.notification.infrastructure.persistence;

import com.securepass.modules.notification.application.OpenGuardsAtGateQuery;
import lombok.AllArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@AllArgsConstructor
@Repository
public class OpenGuardsAtGateJdbcQuery implements OpenGuardsAtGateQuery {

    private final JdbcClient jdbc;

    @Override
    public List<UUID> findGuardUserIds(UUID neighborhoodId, UUID gateId) {
        // Filtro por neighborhood_id ademas de gate_id: barato con `idx_shifts_gate`
        // y protege ante la (imposible por FK actual) situacion de un gate
        // relocalizado a otro barrio.
        return jdbc.sql("""
                SELECT guard_id
                  FROM guard_shifts
                 WHERE neighborhood_id = :nid
                   AND gate_id = :gate
                   AND ended_at IS NULL
                """)
                .param("nid", neighborhoodId)
                .param("gate", gateId)
                .query((rs, i) -> rs.getObject("guard_id", UUID.class))
                .list();
    }
}
