package com.securepass.smoke;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.domain.Role;
import com.securepass.shared.security.JwtService;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WBS-47 / 48 / 50: smoke HTTP contra Postgres real de los tres endpoints
 * de metricas. Cubre: shape de la respuesta, aislamiento por barrio
 * (admin de A no ve datos de B), clamp de 90 dias en /traffic, y el
 * caso overstay (stay que excede stay_alert_minutes vs una que no).
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminMetricsHttpTest {

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

    @Test
    void metricsIsolatedPerNeighborhood() throws Exception {
        UUID nbhA = UUID.randomUUID();
        UUID nbhB = UUID.randomUUID();
        UUID adminA = UUID.randomUUID();
        UUID lotA = UUID.randomUUID(), lotB = UUID.randomUUID();
        UUID gateA = UUID.randomUUID(), gateB = UUID.randomUUID();

        seedNeighborhood(nbhA, "A");
        seedNeighborhood(nbhB, "B");
        seedUser(adminA, "adminA@t.com");
        seedLot(lotA, nbhA, "LA-1");
        seedLot(lotB, nbhB, "LB-1");
        seedGate(gateA, nbhA);
        seedGate(gateB, nbhB);

        // Barrio A: 2 ingresos, 1 egreso, 1 denegado (INVALID_SIGNATURE).
        Instant now = Instant.now();
        insertLog(nbhA, gateA, "IN",  "ALLOWED", null, now.minusSeconds(3600));
        insertLog(nbhA, gateA, "IN",  "ALLOWED", null, now.minusSeconds(1800));
        insertLog(nbhA, gateA, "OUT", "ALLOWED", null, now.minusSeconds(300));
        insertLog(nbhA, gateA, "IN",  "DENIED",  "INVALID_SIGNATURE", now.minusSeconds(200));
        // Barrio B: RUIDO — 5 ingresos que NO tienen que aparecer en A.
        for (int i = 0; i < 5; i++) {
            insertLog(nbhB, gateB, "IN", "ALLOWED", null, now.minusSeconds(1000 + i));
        }

        String jwtAdminA = jwt.issue(adminA, nbhA, List.of(Role.ADMIN), List.of()).token();

        String overview = get("/api/v1/admin/metrics/overview", jwtAdminA);
        assertThat(overview).contains("\"allowedIn\":2");
        assertThat(overview).contains("\"allowedOut\":1");
        assertThat(overview).contains("\"denied\":1");
        assertThat(overview).contains("\"INVALID_SIGNATURE\":1");
        // Confirmacion negativa: no ve los 5 de B (allowedIn = 2, no 7).
        assertThat(overview).doesNotContain("\"allowedIn\":7");
    }

    @Test
    void trafficRangeClampAt90Days() throws Exception {
        UUID nbh = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        seedNeighborhood(nbh, "R");
        seedUser(admin, "clamp@t.com");
        String token = jwt.issue(admin, nbh, List.of(Role.ADMIN), List.of()).token();

        // 91 dias -> 400
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = from.plus(Duration.ofDays(91));
        HttpResponse<String> resp = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/admin/metrics/traffic"
                        + "?from=" + from + "&to=" + to))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(5))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(422); // BusinessRuleViolation -> 422
        assertThat(resp.body()).contains("traffic-range");

        // 90 dias exactos -> 200
        Instant to90 = from.plus(Duration.ofDays(90));
        HttpResponse<String> ok = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/admin/metrics/traffic"
                        + "?from=" + from + "&to=" + to90))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(5))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(ok.statusCode()).isEqualTo(200);
    }

    @Test
    void overstaysListsOnlyStaysExceedingAlertMinutes() throws Exception {
        UUID nbh = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID lot = UUID.randomUUID();
        UUID gate = UUID.randomUUID();
        seedNeighborhood(nbh, "O");
        seedUser(admin, "over@t.com");
        seedLot(lot, nbh, "L-1");
        seedGate(gate, nbh);
        // stay_alert_minutes por defecto = 480 (8h). Setteamos 60 min para simplificar.
        jdbc.update("UPDATE neighborhoods SET stay_alert_minutes = 60 WHERE id = ?", nbh);

        Instant now = Instant.now();
        // Overstay: entrada hace 2h, sin salida.
        seedStay(nbh, lot, "Overstayed Larga", "30111111", now.minusSeconds(7200));
        // NO overstay: entrada hace 30 min.
        seedStay(nbh, lot, "Reciente Corta", "30222222", now.minusSeconds(1800));

        String token = jwt.issue(admin, nbh, List.of(Role.ADMIN), List.of()).token();
        String body = get("/api/v1/admin/metrics/overstays", token);
        assertThat(body).contains("Overstayed Larga");
        assertThat(body).doesNotContain("Reciente Corta");
    }

    @Test
    void trafficBucketsAlignedToNeighborhoodTimezoneNotUtc() throws Exception {
        // Kolkata (UTC+5:30) tiene offset fraccional. Un log a las 06:20 UTC
        // cae en el bucket local 11:00 Kolkata = 05:30 UTC. Si estuviera
        // alineado a UTC, seria 06:00 UTC. La diferencia detecta cualquier
        // regresion al date_trunc sin AT TIME ZONE.
        UUID nbh = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID gate = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO neighborhoods (id, name, slug, timezone)
                VALUES (?, ?, ?, 'Asia/Kolkata')
                """, nbh, "K-" + nbh, "k-" + nbh);
        seedUser(admin, "kolkata@t.com");
        seedGate(gate, nbh);
        // 2026-06-15 06:20:00Z = 11:50 IST -> bucket local 11:00 IST = 05:30 UTC.
        insertLog(nbh, gate, "IN", "ALLOWED", null, Instant.parse("2026-06-15T06:20:00Z"));

        String token = jwt.issue(admin, nbh, List.of(Role.ADMIN), List.of()).token();
        String body = get("/api/v1/admin/metrics/traffic"
                + "?from=2026-06-15T00:00:00Z&to=2026-06-16T00:00:00Z", token);
        assertThat(body).contains("\"start\":\"2026-06-15T05:30:00Z\"");
        assertThat(body).doesNotContain("\"start\":\"2026-06-15T06:00:00Z\"");
    }

    // ---------------- seeding helpers ----------------

    private void seedNeighborhood(UUID id, String label) {
        jdbc.update("""
                INSERT INTO neighborhoods (id, name, slug, timezone)
                VALUES (?, ?, ?, 'America/Argentina/Mendoza')
                """, id, label + "-" + id, "s-" + id);
    }

    private void seedUser(UUID id, String email) {
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, 'h', 'X', 'ACTIVE')
                """, id, email);
    }

    private void seedLot(UUID id, UUID nbh, String code) {
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, ?)", id, nbh, code);
    }

    private void seedGate(UUID id, UUID nbh) {
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, 'G', 'BOTH')", id, nbh);
    }

    private void insertLog(UUID nbh, UUID gate, String dir, String result,
                           String denialReason, Instant capturedAt) {
        jdbc.update("""
                INSERT INTO access_logs
                    (neighborhood_id, gate_id, direction, subject_type, result, denial_reason,
                     captured_at, client_event_id)
                VALUES (?, ?, ?, 'GUEST', ?, ?, ?, ?)
                """, nbh, gate, dir, result, denialReason,
                java.sql.Timestamp.from(capturedAt), UUID.randomUUID());
    }

    private void seedStay(UUID nbh, UUID lot, String name, String dni, Instant enteredAt) {
        UUID entryLog = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO access_logs
                    (id, neighborhood_id, gate_id, direction, subject_type, result,
                     captured_at, client_event_id)
                VALUES (?, ?, (SELECT id FROM gates WHERE neighborhood_id = ? LIMIT 1),
                        'IN', 'GUEST', 'ALLOWED', ?, ?)
                """, entryLog, nbh, nbh, java.sql.Timestamp.from(enteredAt), UUID.randomUUID());
        jdbc.update("""
                INSERT INTO stays
                    (neighborhood_id, lot_id, subject_name, subject_document,
                     entry_log_id, entered_at, expected_until)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, nbh, lot, name, dni, entryLog,
                java.sql.Timestamp.from(enteredAt),
                java.sql.Timestamp.from(enteredAt.plus(Duration.ofHours(4))));
    }

    private String get(String path, String jwtToken) throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + jwtToken)
                .timeout(Duration.ofSeconds(5))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as("status of GET %s", path).isEqualTo(200);
        return resp.body();
    }
}
