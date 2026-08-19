package com.securepass.modules.access.application;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.access.domain.AccessDecision;
import com.securepass.modules.access.domain.Direction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RecordAccessAttemptIdempotencyTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired RecordAccessAttemptUseCase useCase;
    @MockBean ValidateAccessUseCase validator;

    UUID neighborhoodId;
    UUID gateId;
    UUID guardId;
    UUID lotId;
    UUID invitationId;
    UUID jti;
    UUID clientEventId;

    @BeforeEach
    void seed() {
        neighborhoodId = UUID.randomUUID();
        gateId = UUID.randomUUID();
        guardId = UUID.randomUUID();
        lotId = UUID.randomUUID();
        invitationId = UUID.randomUUID();
        jti = UUID.randomUUID();
        clientEventId = UUID.randomUUID();

        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, ?, ?)",
                neighborhoodId, "Barrio", "b-" + UUID.randomUUID());
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, ?)",
                lotId, neighborhoodId, "L-" + lotId.toString().substring(0, 8));
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, ?, 'BOTH')",
                gateId, neighborhoodId, "G-" + gateId.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name)
                VALUES (?, ?, ?, ?)
                """, guardId, "g-" + UUID.randomUUID() + "@t.com", "hash", "Guardia");
        UUID creatorId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name)
                VALUES (?, ?, ?, ?)
                """, creatorId, "u-" + UUID.randomUUID() + "@t.com", "hash", "Titular");
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status,
                    guest_name, companions,
                    valid_from, valid_until, max_uses, used_count,
                    token_kid, jti
                ) VALUES (?, ?, ?, ?, 'SINGLE', 'ACTIVE', 'Maria', 0, ?, ?, 1, 0, 'kid-1', ?)
                """,
                invitationId, neighborhoodId, lotId, creatorId,
                Timestamp.from(now.minusSeconds(60)),
                Timestamp.from(now.plusSeconds(3600)), jti);

        when(validator.validate(any(), any())).thenReturn(
                new AccessDecision.Allowed(invitationId, jti, "Maria", "M4-L12", 0));
    }

    @Test
    void reintentoConMismoClientEventIdDevuelveMismoLogSinDuplicar() {
        RecordAccessAttemptUseCase.Command cmd = new RecordAccessAttemptUseCase.Command(
                neighborhoodId, gateId, guardId, "SP1.token", Direction.IN,
                0, Instant.now(), clientEventId, false);

        RecordAccessAttemptUseCase.Result first = useCase.record(cmd);
        RecordAccessAttemptUseCase.Result retry = useCase.record(cmd);

        assertThat(first.log().id()).isEqualTo(retry.log().id());
        assertThat(first.decision()).isInstanceOf(AccessDecision.Allowed.class);
        assertThat(retry.decision()).isInstanceOf(AccessDecision.Allowed.class);

        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM access_logs WHERE client_event_id = ?",
                Integer.class, clientEventId);
        assertThat(rowCount).as("access_logs no debe duplicar por retry").isEqualTo(1);

        Integer usedCount = jdbc.queryForObject(
                "SELECT used_count FROM invitations WHERE id = ?",
                Integer.class, invitationId);
        assertThat(usedCount).as("used_count no debe incrementar dos veces").isEqualTo(1);
    }
}
