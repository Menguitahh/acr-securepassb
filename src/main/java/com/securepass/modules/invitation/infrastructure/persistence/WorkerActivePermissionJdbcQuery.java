package com.securepass.modules.invitation.infrastructure.persistence;

import com.securepass.modules.invitation.application.WorkerActivePermissionQuery;
import lombok.AllArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@AllArgsConstructor
@Repository
public class WorkerActivePermissionJdbcQuery implements WorkerActivePermissionQuery {

    private final JdbcClient jdbc;

    @Override
    public Optional<UUID> findNeighborhoodOfActiveWorker(UUID userId) {
        return jdbc.sql("""
                SELECT l.neighborhood_id
                  FROM lot_permissions p
                  JOIN lots l ON l.id = p.lot_id
                 WHERE p.user_id = :uid
                   AND p.permission_type = 'WORKER'
                   AND p.active = TRUE
                 ORDER BY l.neighborhood_id
                 LIMIT 1
                """)
                .param("uid", userId)
                .query(UUID.class)
                .optional();
    }
}
