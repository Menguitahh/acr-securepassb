package com.securepass.modules.shift.infrastructure.rest;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.domain.Role;
import com.securepass.shared.security.JwtTokenProvider;
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
 * securepassf#96: sin turno abierto, GET /api/v1/shifts/current devuelve 204.
 * Con turno abierto, 200 + ShiftResponse.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShiftCurrentHttpTest {

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenProvider jwt;

    UUID barrio;
    UUID gate;
    UUID guardId;
    HttpClient http;

    @BeforeEach
    void seed() {
        http = HttpClient.newHttpClient();
        barrio = UUID.randomUUID();
        gate = UUID.randomUUID();
        guardId = UUID.randomUUID();

        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'B', ?)",
                barrio, "b-" + UUID.randomUUID());
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, 'G', 'BOTH')",
                gate, barrio);
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, 'h', 'guard', 'ACTIVE')
                """, guardId, "g-" + guardId + "@t.com");
        jdbc.update("""
                INSERT INTO memberships (user_id, neighborhood_id, role, active)
                VALUES (?, ?, 'GUARD', TRUE)
                """, guardId, barrio);
    }

    private HttpResponse<String> get() throws Exception {
        String token = jwt.issue(guardId, barrio, List.of(Role.GUARD), List.of()).token();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/shifts/current"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void sinTurnoAbiertoDevuelve204SinBody() throws Exception {
        HttpResponse<String> resp = get();
        assertThat(resp.statusCode()).isEqualTo(204);
        assertThat(resp.body()).isEmpty();
    }

    @Test
    void conTurnoAbiertoDevuelve200ConShiftResponse() throws Exception {
        UUID shiftId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO guard_shifts (id, neighborhood_id, gate_id, guard_id)
                VALUES (?, ?, ?, ?)
                """, shiftId, barrio, gate, guardId);

        HttpResponse<String> resp = get();
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body())
                .contains(shiftId.toString())
                .contains(gate.toString())
                .contains(guardId.toString())
                .contains("startedAt");
    }
}
