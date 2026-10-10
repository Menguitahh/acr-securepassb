package com.securepass.modules.access.infrastructure.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * WBS-36 / US-24: ingreso manual sin red. La garita guarda los lotes activos
 * del barrio (GET /lots/active), encola el ingreso con su clientEventId y lo
 * reenvia al volver la red: el reenvio no duplica. La visita imprevista
 * lleva los acompanantes que carga el guardia.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ManualEntryOfflineHttpTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenProvider jwt;
    @Autowired ObjectMapper json;

    final HttpClient http = HttpClient.newHttpClient();
    UUID barrio;
    UUID gate;
    UUID guard;
    UUID lot;

    @BeforeEach
    void seed() {
        barrio = UUID.randomUUID();
        UUID otro = UUID.randomUUID();
        gate = UUID.randomUUID();
        guard = UUID.randomUUID();
        lot = UUID.randomUUID();
        for (UUID n : List.of(barrio, otro)) {
            jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'B', ?)", n, "b-" + n);
        }
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, 'G', 'BOTH')", gate, barrio);
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, 'h', 'Guardia', 'ACTIVE')",
                guard, "g-" + guard + "@t.com");
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'A-1')", lot, barrio);
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code, status) VALUES (?, ?, 'A-2', 'SUSPENDED')",
                UUID.randomUUID(), barrio);
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'Z-9')", UUID.randomUUID(), otro);
    }

    private void openShift() {
        jdbc.update("INSERT INTO guard_shifts (id, neighborhood_id, gate_id, guard_id, started_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), barrio, gate, guard, Timestamp.from(Instant.now().minusSeconds(60)));
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + jwt.issue(guard, barrio, List.of(Role.GUARD), List.of()).token())
                .header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String manual(UUID clientEventId, int companions) {
        return "{\"subjectName\":\"Plomero\",\"subjectDocument\":\"30123456\",\"lotId\":\"" + lot + "\","
                + "\"gateId\":\"" + gate + "\",\"clientEventId\":\"" + clientEventId + "\","
                + "\"capturedAt\":\"" + Instant.now().minusSeconds(120) + "\",\"companions\":" + companions + "}";
    }

    @Test
    void lotesActivosDelBarrioParaGuardarSinRed() throws Exception {
        openShift();

        HttpResponse<String> resp = send("GET", "/api/v1/lots/active", null);

        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        List<String> codes = new ArrayList<>();
        json.readTree(resp.body()).forEach(l -> codes.add(l.get("code").asText()));
        assertThat(codes).containsExactly("A-1");
    }

    @Test
    void sinTurnoNoHayLotes() throws Exception {
        assertThat(send("GET", "/api/v1/lots/active", null).statusCode()).isEqualTo(409);
    }

    @Test
    void reenviarElMismoIngresoNoDuplicaYGuardaLosAcompanantes() throws Exception {
        openShift();
        UUID ev = UUID.randomUUID();

        assertThat(send("POST", "/api/v1/access/manual", manual(ev, 3)).statusCode()).isEqualTo(201);
        assertThat(send("POST", "/api/v1/access/manual", manual(ev, 3)).statusCode()).isBetween(200, 201);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM access_logs WHERE client_event_id = ?", Integer.class, ev))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT companions FROM access_logs WHERE client_event_id = ?", Integer.class, ev))
                .isEqualTo(3);
    }

    @Test
    void masDeDiezAcompanantesEs400() throws Exception {
        openShift();
        assertThat(send("POST", "/api/v1/access/manual", manual(UUID.randomUUID(), 11)).statusCode()).isEqualTo(400);
    }
}
