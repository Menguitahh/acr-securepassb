package com.securepass.modules.shift.infrastructure.persistence;

import com.securepass.modules.access.application.OpenShiftQuery;
import lombok.AllArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@AllArgsConstructor
@Repository
public class OpenShiftJdbcQuery implements OpenShiftQuery {

    private final JdbcClient jdbc;

    @Override
    public boolean hasOpenShift(UUID guardId) {
        return jdbc.sql("""
                SELECT 1 FROM guard_shifts
                 WHERE guard_id = :guard AND ended_at IS NULL
                 LIMIT 1
                """)
                .param("guard", guardId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    @Override
    public java.util.Optional<UUID> currentShiftId(UUID guardId) {
        return jdbc.sql("""
                SELECT id FROM guard_shifts
                 WHERE guard_id = :guard AND ended_at IS NULL
                 ORDER BY started_at DESC
                 LIMIT 1
                """)
                .param("guard", guardId)
                .query(UUID.class)
                .optional();
    }
}
