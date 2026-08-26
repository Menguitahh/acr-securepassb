package com.securepass.modules.access;

import java.util.UUID;

import com.securepass.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AccessLogsImmutabilityTest {

    @Autowired
    JdbcTemplate jdbc;

    UUID neighborhoodId;
    UUID gateId;
    UUID logId;

    @BeforeEach
    void seedAndInsertOneLog() {
        neighborhoodId = UUID.randomUUID();
        gateId = UUID.randomUUID();
        logId = UUID.randomUUID();

        jdbc.update("""
            INSERT INTO neighborhoods (id, name, slug) VALUES (?, ?, ?)
            """, neighborhoodId, "Barrio Test", "barrio-test-" + UUID.randomUUID());
        jdbc.update("""
            INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, ?, 'BOTH')
            """, gateId, neighborhoodId, "Principal-" + UUID.randomUUID());
        jdbc.update("""
            INSERT INTO access_logs (
                id, neighborhood_id, gate_id, direction, subject_type, result,
                captured_at, client_event_id
            ) VALUES (?, ?, ?, 'IN', 'MANUAL', 'ALLOWED', now(), ?)
            """, logId, neighborhoodId, gateId, UUID.randomUUID());
    }

    @Test
    void updateOnAccessLogsThrows() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE access_logs SET direction = 'OUT' WHERE id = ?", logId))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("access_logs es append-only");
    }

    @Test
    void deleteOnAccessLogsThrows() {
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM access_logs WHERE id = ?", logId))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("access_logs es append-only");
    }
}
