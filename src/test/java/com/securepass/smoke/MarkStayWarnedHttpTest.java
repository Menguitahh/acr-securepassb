package com.securepass.smoke;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.domain.Role;
import com.securepass.shared.security.JwtTokenProvider;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WBS-69: smoke HTTP de POST /api/v1/stays/{id}/mark-warned. Verifica
 * el wiring del controller: rol GUARD, persistencia de warned_at,
 * idempotencia (segunda llamada devuelve el mismo timestamp), y que
 * GET /stays/open refleja warned=true.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MarkStayWarnedHttpTest {

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

    @Test
    void markWarnedIsIdempotentAndReflectedInOpenStays() throws Exception {
        UUID nbh = UUID.randomUUID();
        UUID gate = UUID.randomUUID();
        UUID lot = UUID.randomUUID();
        UUID guard = UUID.randomUUID();
        UUID resident = UUID.randomUUID();
        seedNeighborhood(nbh);
        seedGate(gate, nbh);
        seedLot(lot, nbh, "L-1");
        seedUser(guard, "g-" + UUID.randomUUID() + "@t.com");
        seedUser(resident, "r-" + UUID.randomUUID() + "@t.com");
        UUID stayId = seedOpenStay(nbh, gate, lot, resident, "Maria Lopez", "30123456");

        String token = jwt.issue(guard, nbh, List.of(Role.GUARD), List.of()).token();

        HttpResponse<String> first = post("/api/v1/stays/" + stayId + "/mark-warned", token);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).contains("\"stayId\":\"" + stayId + "\"");
        assertThat(first.body()).contains("\"warnedAt\":\"");

        Timestamp dbWarned = jdbc.queryForObject(
                "SELECT warned_at FROM stays WHERE id = ?", Timestamp.class, stayId);
        assertThat(dbWarned).isNotNull();
        Instant original = dbWarned.toInstant();

        HttpResponse<String> second = post("/api/v1/stays/" + stayId + "/mark-warned", token);
        assertThat(second.statusCode()).isEqualTo(200);
        // Idempotente: la segunda llamada conserva el timestamp original.
        Timestamp dbWarnedAfter = jdbc.queryForObject(
                "SELECT warned_at FROM stays WHERE id = ?", Timestamp.class, stayId);
        assertThat(dbWarnedAfter.toInstant()).isEqualTo(original);

        // /stays/open ahora refleja warned=true para esta stay.
        HttpResponse<String> open = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/stays/open"))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(5))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(open.statusCode()).isEqualTo(200);
        assertThat(open.body()).contains(stayId.toString());
        assertThat(open.body()).contains("\"warned\":true");
    }

    @Test
    void markWarnedOnClosedStayReturns409() throws Exception {
        UUID nbh = UUID.randomUUID();
        UUID gate = UUID.randomUUID();
        UUID lot = UUID.randomUUID();
        UUID guard = UUID.randomUUID();
        UUID resident = UUID.randomUUID();
        seedNeighborhood(nbh);
        seedGate(gate, nbh);
        seedLot(lot, nbh, "L-1");
        seedUser(guard, "g-" + UUID.randomUUID() + "@t.com");
        seedUser(resident, "r-" + UUID.randomUUID() + "@t.com");
        UUID stayId = seedOpenStay(nbh, gate, lot, resident, "Ya Salio", "30999999");
        jdbc.update("UPDATE stays SET exited_at = now() WHERE id = ?", stayId);

        String token = jwt.issue(guard, nbh, List.of(Role.GUARD), List.of()).token();
        HttpResponse<String> resp = post("/api/v1/stays/" + stayId + "/mark-warned", token);
        assertThat(resp.statusCode()).isEqualTo(409);
    }

    // ---------------- helpers ----------------

    private void seedNeighborhood(UUID id) {
        jdbc.update("""
                INSERT INTO neighborhoods (id, name, slug, timezone)
                VALUES (?, ?, ?, 'America/Argentina/Mendoza')
                """, id, "N-" + id, "s-" + id);
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

    private UUID seedOpenStay(UUID nbh, UUID gate, UUID lot, UUID resident, String name, String dni) {
        UUID invitationId = UUID.randomUUID();
        UUID entryLog = UUID.randomUUID();
        UUID stayId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status,
                    guest_name, guest_document, companions, valid_from, valid_until,
                    max_uses, used_count, token_kid, jti
                ) VALUES (?, ?, ?, ?, 'SINGLE', 'ACTIVE', ?, ?, 0, ?, ?, 1, 0, 'kid', ?)
                """, invitationId, nbh, lot, resident, name, dni,
                Timestamp.from(now.minusSeconds(60)), Timestamp.from(now.plusSeconds(3600)),
                UUID.randomUUID());
        jdbc.update("""
                INSERT INTO access_logs (
                    id, neighborhood_id, gate_id, lot_id,
                    direction, subject_type, result,
                    subject_name, captured_at, client_event_id
                ) VALUES (?, ?, ?, ?, 'IN', 'GUEST', 'ALLOWED', ?, ?, ?)
                """, entryLog, nbh, gate, lot, name,
                Timestamp.from(now.minusSeconds(1800)), UUID.randomUUID());
        jdbc.update("""
                INSERT INTO stays (id, neighborhood_id, invitation_id, lot_id,
                                   subject_name, subject_document, entry_log_id,
                                   entered_at, expected_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, stayId, nbh, invitationId, lot, name, dni, entryLog,
                Timestamp.from(now.minusSeconds(1800)), Timestamp.from(now.plusSeconds(1800)));
        return stayId;
    }

    private HttpResponse<String> post(String path, String jwtToken) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + jwtToken)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
