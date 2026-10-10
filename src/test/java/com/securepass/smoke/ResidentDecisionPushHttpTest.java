package com.securepass.smoke;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.notification.application.PushSender;
import com.securepass.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * WBS-72: end-to-end del push server->garita cuando el residente decide.
 * Verifica que POST /invitations/{id}/extend y /request-exit emiten push
 * al guardia con turno abierto en el gate del ingreso, AFTER_COMMIT y
 * en el pushExecutor. Skip silencioso si el gate no tiene turno abierto.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResidentDecisionPushHttpTest {

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

    // Reemplaza LoggingPushSender/ExpoPushSender por un mock: el listener
    // corre AFTER_COMMIT en pushExecutor, por eso los verify usan timeout().
    @MockBean PushSender push;

    @Test
    void extendEmitePushEXTENDEDalGuardiaEnTurno() throws Exception {
        Fixture f = seedFullFixture();
        UUID guardId = seedOpenShift(f);

        String token = jwt.issue(f.residentId, f.nbh, List.of(Role.RESIDENT_OWNER), List.of(f.lotId)).token();
        HttpResponse<String> resp = post("/api/v1/invitations/" + f.invitationId + "/extend", token);
        assertThat(resp.statusCode()).isEqualTo(200);

        ArgumentCaptor<PushSender.GuardResidentDecision> payload =
                ArgumentCaptor.forClass(PushSender.GuardResidentDecision.class);
        verify(push, timeout(3000)).notifyGuardOfResidentDecision(eq(guardId), payload.capture());
        assertThat(payload.getValue().kind()).isEqualTo(PushSender.GuardResidentDecision.Kind.EXTENDED);
        assertThat(payload.getValue().subjectName()).isEqualTo("Juan Perez");
        assertThat(payload.getValue().residentName()).isEqualTo("Ana Lopez");
    }

    @Test
    void requestExitEmitePushEXIT_REQUESTEDalGuardiaEnTurno() throws Exception {
        Fixture f = seedFullFixture();
        UUID guardId = seedOpenShift(f);

        String token = jwt.issue(f.residentId, f.nbh, List.of(Role.RESIDENT_OWNER), List.of(f.lotId)).token();
        HttpResponse<String> resp = post("/api/v1/invitations/" + f.invitationId + "/request-exit", token);
        assertThat(resp.statusCode()).isEqualTo(200);

        ArgumentCaptor<PushSender.GuardResidentDecision> payload =
                ArgumentCaptor.forClass(PushSender.GuardResidentDecision.class);
        verify(push, timeout(3000)).notifyGuardOfResidentDecision(eq(guardId), payload.capture());
        assertThat(payload.getValue().kind()).isEqualTo(PushSender.GuardResidentDecision.Kind.EXIT_REQUESTED);
    }

    @Test
    void extendConGateSinTurnoAbiertoNoEmitePush() throws Exception {
        Fixture f = seedFullFixture();
        // Deliberadamente NO se crea guard_shift en este gate.

        String token = jwt.issue(f.residentId, f.nbh, List.of(Role.RESIDENT_OWNER), List.of(f.lotId)).token();
        HttpResponse<String> resp = post("/api/v1/invitations/" + f.invitationId + "/extend", token);
        assertThat(resp.statusCode()).isEqualTo(200);

        // No hay guardia al que mandar. Esperamos hasta 1s para descartar el
        // push async, y luego verificamos que nunca se llamo al sender.
        verify(push, after(1000).never()).notifyGuardOfResidentDecision(any(), any());
    }

    // ---------------- fixture ----------------

    private record Fixture(UUID nbh, UUID gateId, UUID lotId, UUID residentId, UUID invitationId) {}

    private Fixture seedFullFixture() {
        UUID nbh = UUID.randomUUID();
        UUID gate = UUID.randomUUID();
        UUID lot = UUID.randomUUID();
        UUID resident = UUID.randomUUID();
        UUID invitation = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO neighborhoods (id, name, slug, timezone)
                VALUES (?, ?, ?, 'America/Argentina/Mendoza')
                """, nbh, "N-" + nbh, "s-" + nbh);
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, 'G', 'BOTH')", gate, nbh);
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, ?)",
                lot, nbh, "L-" + lot.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, 'h', 'Ana Lopez', 'ACTIVE')
                """, resident, "res-" + resident + "@t.com");
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'OWNER', jsonb_build_object('can_invite', TRUE, 'relation', 'OWNER'), TRUE)
                """, resident, lot);

        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status,
                    guest_name, guest_document, companions, valid_from, valid_until,
                    max_uses, used_count, token_kid, jti
                ) VALUES (?, ?, ?, ?, 'SINGLE', 'ACTIVE', 'Juan Perez', '30123456', 0, ?, ?, 1, 0, 'kid', ?)
                """, invitation, nbh, lot, resident,
                Timestamp.from(now.minusSeconds(60)),
                Timestamp.from(now.plusSeconds(3600)),
                UUID.randomUUID());

        UUID entryLog = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO access_logs (
                    id, neighborhood_id, gate_id, lot_id, invitation_id,
                    direction, subject_type, result,
                    subject_name, captured_at, client_event_id
                ) VALUES (?, ?, ?, ?, ?, 'IN', 'GUEST', 'ALLOWED', 'Juan Perez', ?, ?)
                """, entryLog, nbh, gate, lot, invitation,
                Timestamp.from(now.minusSeconds(1800)), UUID.randomUUID());

        UUID stayId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO stays (id, neighborhood_id, invitation_id, lot_id,
                                   subject_name, subject_document, entry_log_id,
                                   entered_at, expected_until)
                VALUES (?, ?, ?, ?, 'Juan Perez', '30123456', ?, ?, ?)
                """, stayId, nbh, invitation, lot, entryLog,
                Timestamp.from(now.minusSeconds(1800)), Timestamp.from(now.plusSeconds(1800)));

        return new Fixture(nbh, gate, lot, resident, invitation);
    }

    private UUID seedOpenShift(Fixture f) {
        UUID guard = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, 'h', 'Guardia Uno', 'ACTIVE')
                """, guard, "g-" + guard + "@t.com");
        jdbc.update("""
                INSERT INTO guard_shifts (id, neighborhood_id, gate_id, guard_id, started_at)
                VALUES (?, ?, ?, ?, now())
                """, UUID.randomUUID(), f.nbh, f.gateId, guard);
        return guard;
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
