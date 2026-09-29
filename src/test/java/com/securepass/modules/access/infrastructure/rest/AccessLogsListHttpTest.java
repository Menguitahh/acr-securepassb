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
 * GET /api/v1/access-logs: el rango es [from, to), igual que metricas y
 * export (#197). Un evento en exactamente `to` no pertenece al rango (#199).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessLogsListHttpTest {

    private static final Instant FROM = Instant.parse("2026-09-01T03:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-02T03:00:00Z");

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    UUID barrio;
    UUID gate;
    UUID adminId;

    @BeforeEach
    void seed() {
        barrio = UUID.randomUUID();
        gate = UUID.randomUUID();
        adminId = UUID.randomUUID();
        jdbc.update("INSERT INTO neighborhoods (id, name, slug, timezone) VALUES (?, 'B', ?, 'America/Argentina/Mendoza')",
                barrio, "b-" + UUID.randomUUID());
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, 'G', 'BOTH')", gate, barrio);
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, 'h', 'admin', 'ACTIVE')
                """, adminId, "a-" + adminId + "@t.com");
    }

    private void insertLog(String name, Instant at) {
        jdbc.update("""
                INSERT INTO access_logs (
                    id, neighborhood_id, gate_id, direction, subject_type, result,
                    subject_name, companions, captured_at, client_event_id
                ) VALUES (?, ?, ?, 'IN', 'GUEST', 'ALLOWED', ?, 0, ?, ?)
                """, UUID.randomUUID(), barrio, gate, name, Timestamp.from(at), UUID.randomUUID());
    }

    private JsonNode list() throws Exception {
        String token = jwt.issue(adminId, barrio, List.of(Role.ADMIN), List.of()).token();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/access-logs?from=" + FROM + "&to=" + TO))
                .header("Authorization", "Bearer " + token)
                .GET().build();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return json.readTree(resp.body());
    }

    private static List<String> names(JsonNode body) {
        List<String> names = new ArrayList<>();
        body.get("items").forEach(item -> names.add(item.get("subjectName").asText()));
        return names;
    }

    @Test
    void elEventoEnExactamenteToQuedaAfueraDeItemsYDelTotal() throws Exception {
        insertLog("en el borde", TO);

        JsonNode body = list();

        assertThat(names(body)).isEmpty();
        assertThat(body.get("total").asLong()).isZero();
    }

    @Test
    void unMicrosegundoAntesDeToEntra() throws Exception {
        insertLog("ultimo del rango", TO.minusNanos(1_000));
        insertLog("primero del rango", FROM);

        JsonNode body = list();

        assertThat(names(body)).containsExactly("ultimo del rango", "primero del rango");
        assertThat(body.get("total").asLong()).isEqualTo(2);
    }
}
