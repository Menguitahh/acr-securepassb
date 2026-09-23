package com.securepass.modules.shift.infrastructure.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.domain.Role;
import com.securepass.shared.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US-33: GET /api/v1/shifts trae nombre del guardia, garita y cantidad de
 * accesos del turno, para que el dashboard no tenga que resolver UUIDs.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShiftListHttpTest {

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;

    UUID barrio;
    UUID gate;
    UUID guardId;
    UUID adminId;

    @BeforeEach
    void seed() {
        barrio = UUID.randomUUID();
        gate = UUID.randomUUID();
        guardId = UUID.randomUUID();
        adminId = UUID.randomUUID();

        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'B', ?)",
                barrio, "b-" + UUID.randomUUID());
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, 'Garita Norte', 'BOTH')",
                gate, barrio);
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, 'h', 'Juan Perez', 'ACTIVE')
                """, guardId, "g-" + guardId + "@t.com");
    }

    private void insertLog(UUID shiftId) {
        jdbc.update("""
                INSERT INTO access_logs (
                    id, neighborhood_id, gate_id, shift_id, direction, subject_type, result,
                    captured_at, client_event_id
                ) VALUES (?, ?, ?, ?, 'IN', 'MANUAL', 'ALLOWED', now(), ?)
                """, UUID.randomUUID(), barrio, gate, shiftId, UUID.randomUUID());
    }

    @Test
    void listadoIncluyeGuardiaGaritaYCantidadDeAccesos() throws Exception {
        UUID closed = UUID.randomUUID();
        UUID open = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO guard_shifts (id, neighborhood_id, gate_id, guard_id, started_at, ended_at)
                VALUES (?, ?, ?, ?, now() - interval '10 hours', now() - interval '2 hours')
                """, closed, barrio, gate, guardId);
        jdbc.update("""
                INSERT INTO guard_shifts (id, neighborhood_id, gate_id, guard_id, started_at)
                VALUES (?, ?, ?, ?, now() - interval '1 hour')
                """, open, barrio, gate, guardId);
        insertLog(closed);
        insertLog(closed);
        insertLog(null);

        String token = jwt.issue(adminId, barrio, List.of(Role.ADMIN), List.of()).token();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/v1/shifts"))
                        .header("Authorization", "Bearer " + token)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = new ObjectMapper().readTree(resp.body());
        assertThat(body).hasSize(2);
        JsonNode openShift = body.get(0);
        JsonNode closedShift = body.get(1);
        assertThat(openShift.get("id").asText()).isEqualTo(open.toString());
        assertThat(openShift.get("accessCount").asLong()).isZero();
        assertThat(closedShift.get("id").asText()).isEqualTo(closed.toString());
        assertThat(closedShift.get("guardFullName").asText()).isEqualTo("Juan Perez");
        assertThat(closedShift.get("gateName").asText()).isEqualTo("Garita Norte");
        assertThat(closedShift.get("accessCount").asLong()).isEqualTo(2);
    }
}
