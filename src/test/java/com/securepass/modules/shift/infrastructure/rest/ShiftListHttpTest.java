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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US-33: GET /api/v1/shifts trae nombre del guardia, garita y cantidad de
 * accesos del turno, para que el dashboard no tenga que resolver UUIDs.
 * Paginado con tamano fijo y total, como /access-logs (#203); el rango es
 * [from, to) sobre started_at.
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

    private UUID insertShift(Instant startedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO guard_shifts (id, neighborhood_id, gate_id, guard_id, started_at, ended_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, barrio, gate, guardId, Timestamp.from(startedAt), Timestamp.from(startedAt.plusSeconds(60)));
        return id;
    }

    private JsonNode list(String query) throws Exception {
        String token = jwt.issue(adminId, barrio, List.of(Role.ADMIN), List.of()).token();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/v1/shifts" + query))
                        .header("Authorization", "Bearer " + token)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return new ObjectMapper().readTree(resp.body());
    }

    private static List<String> ids(JsonNode body) {
        List<String> ids = new ArrayList<>();
        body.get("items").forEach(item -> ids.add(item.get("id").asText()));
        return ids;
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

        JsonNode body = list("");

        assertThat(body.get("total").asLong()).isEqualTo(2);
        assertThat(body.get("items")).hasSize(2);
        JsonNode openShift = body.get("items").get(0);
        JsonNode closedShift = body.get("items").get(1);
        assertThat(openShift.get("id").asText()).isEqualTo(open.toString());
        assertThat(openShift.get("accessCount").asLong()).isZero();
        assertThat(closedShift.get("id").asText()).isEqualTo(closed.toString());
        assertThat(closedShift.get("guardFullName").asText()).isEqualTo("Juan Perez");
        assertThat(closedShift.get("gateName").asText()).isEqualTo("Garita Norte");
        assertThat(closedShift.get("accessCount").asLong()).isEqualTo(2);
    }

    // Antes cortaba en 200 sin avisar: ahora pagina y total dice cuantos hay.
    @Test
    void paginaDeATamanoFijoConTotal() throws Exception {
        Instant base = Instant.parse("2026-09-01T00:00:00Z");
        List<String> newestFirst = new ArrayList<>();
        for (int i = 0; i < 27; i++) newestFirst.add(0, insertShift(base.plusSeconds(3600L * i)).toString());

        JsonNode first = list("?page=0");
        JsonNode second = list("?page=1");

        assertThat(first.get("page").asInt()).isZero();
        assertThat(first.get("size").asInt()).isEqualTo(25);
        assertThat(first.get("total").asLong()).isEqualTo(27);
        assertThat(ids(first)).containsExactlyElementsOf(newestFirst.subList(0, 25));
        assertThat(second.get("page").asInt()).isEqualTo(1);
        assertThat(second.get("total").asLong()).isEqualTo(27);
        assertThat(ids(second)).containsExactlyElementsOf(newestFirst.subList(25, 27));
    }

    @Test
    void elTurnoQueEmpiezaEnExactamenteToQuedaAfuera() throws Exception {
        Instant from = Instant.parse("2026-09-01T03:00:00Z");
        Instant to = Instant.parse("2026-09-02T03:00:00Z");
        insertShift(to);
        String last = insertShift(to.minusNanos(1_000)).toString();
        String first = insertShift(from).toString();

        JsonNode body = list("?from=" + from + "&to=" + to);

        assertThat(ids(body)).containsExactly(last, first);
        assertThat(body.get("total").asLong()).isEqualTo(2);
    }
}
