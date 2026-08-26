package com.securepass.modules.shift.infrastructure.persistence;

import com.securepass.modules.access.application.OpenShiftQuery;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class OpenShiftJdbcQuery implements OpenShiftQuery {

    private final JdbcClient jdbc;

    public OpenShiftJdbcQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

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
}
