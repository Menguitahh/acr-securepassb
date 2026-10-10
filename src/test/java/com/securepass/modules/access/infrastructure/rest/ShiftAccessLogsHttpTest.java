package com.securepass.modules.access.infrastructure.rest;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US-72: el guardia ve los ingresos y egresos de su turno abierto, con los
 * denegados y su motivo. Nada de otros turnos.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShiftAccessLogsHttpTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    final HttpClient http = HttpClient.newHttpClient();
    UUID barrio;
    UUID gate;
    UUID guard;

    @BeforeEach
    void seed() {
        barrio = UUID.randomUUID();
        gate = UUID.randomUUID();
        guard = UUID.randomUUID();
        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'B', ?)", barrio, "b-" + barrio);
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, 'G', 'BOTH')", gate, barrio);
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, 'h', 'Guardia', 'ACTIVE')",
                guard, "g-" + guard + "@t.com");
    }

    private UUID shift(boolean open) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO guard_shifts (id, neighborhood_id, gate_id, guard_id, started_at, ended_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, barrio, gate, guard, Timestamp.from(Instant.now().minusSeconds(7200)),
                open ? null : Timestamp.from(Instant.now().minusSeconds(3600)));
        return id;
    }

    private void log(UUID shiftId, String name, String direction, String result, String reason, Instant at) {
        jdbc.update("""
                INSERT INTO access_logs (
                    id, neighborhood_id, gate_id, shift_id, guard_id, direction, subject_type, result, denial_reason,
                    subject_name, companions, captured_at, client_event_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'GUEST', ?, ?, ?, 0, ?, ?)
                """, UUID.randomUUID(), barrio, gate, shiftId, guard, direction, result, reason, name,
                Timestamp.from(at), UUID.randomUUID());
    }

    private HttpResponse<String> get(UUID user, List<Role> roles) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/access-logs/shift"))
                .header("Authorization", "Bearer " + jwt.issue(user, barrio, roles, List.of()).token())
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void elGuardiaVeSoloLosAccesosDeSuTurnoAbiertoConLosDenegados() throws Exception {
        UUID viejo = shift(false);
        UUID actual = shift(true);
        Instant now = Instant.now();
        log(viejo, "Turno anterior", "IN", "ALLOWED", null, now.minusSeconds(5000));
        log(actual, "Ana", "IN", "ALLOWED", null, now.minusSeconds(600));
        log(actual, null, "IN", "DENIED", "EXPIRED", now.minusSeconds(300));
        log(actual, "Ana", "OUT", "ALLOWED", null, now.minusSeconds(60));

        HttpResponse<String> resp = get(guard, List.of(Role.GUARD));

        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        JsonNode body = json.readTree(resp.body());
        List<String> seen = new ArrayList<>();
        body.get("items").forEach(i -> seen.add(i.get("direction").asText() + ":" + i.get("result").asText()
                + ":" + i.path("denialReason").asText()));
        assertThat(seen).containsExactly("OUT:ALLOWED:", "IN:DENIED:EXPIRED", "IN:ALLOWED:");
        assertThat(body.get("total").asLong()).isEqualTo(3);
    }

    @Test
    void sinTurnoAbiertoNoHayRegistro() throws Exception {
        log(shift(false), "Turno anterior", "IN", "ALLOWED", null, Instant.now().minusSeconds(5000));

        JsonNode body = json.readTree(get(guard, List.of(Role.GUARD)).body());

        assertThat(body.get("total").asLong()).isZero();
    }

    @Test
    void soloElGuardia() throws Exception {
        assertThat(get(UUID.randomUUID(), List.of(Role.RESIDENT_OWNER)).statusCode()).isEqualTo(403);
    }
}
