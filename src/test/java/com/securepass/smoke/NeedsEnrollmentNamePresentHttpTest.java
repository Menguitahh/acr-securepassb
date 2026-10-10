package com.securepass.smoke;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.invitation.application.QrTokenIssuer;
import com.securepass.modules.invitation.application.SigningKeyIssuer;
import com.securepass.modules.invitation.domain.InvitationType;
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
 * Issue #173: NEEDS_ENROLLMENT en ambos ramos (GUEST y WORKER) devuelve el
 * nombre del sujeto para que el guardia lo cruce contra el documento antes de
 * confirmar el enroll. Este smoke ejerce el ida y vuelta HTTP completo:
 * signing key real, firma Ed25519 real, parseo SP1 real, controller +
 * ValidateAccessResponse. Imprime los bodies crudos para servir de evidencia.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NeedsEnrollmentNamePresentHttpTest {

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
    @Autowired SigningKeyIssuer signingKeys;
    @Autowired QrTokenIssuer qrTokenIssuer;

    @Test
    void guestYWorkerDevuelvenNombreEnNeedsEnrollment() throws Exception {
        UUID nbh = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID worker = UUID.randomUUID();
        UUID guard = UUID.randomUUID();
        UUID lot = UUID.randomUUID();
        UUID gate = UUID.randomUUID();
        UUID invitation = UUID.randomUUID();
        UUID guestJti = UUID.randomUUID();
        String workerDni = "3" + (10_000_000 + (int)(Math.random() * 89_999_999));

        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'Smoke173', ?)",
                nbh, "smoke-173-" + UUID.randomUUID());
        signingKeys.issueForNeighborhood(nbh);
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, 'h', 'Titular', 'ACTIVE')",
                owner, "o-" + owner + "@t.com");
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, document_number, status) VALUES (?, ?, 'h', 'Juan Jardinero', ?, 'ACTIVE')",
                worker, "w-" + worker + "@t.com", workerDni);
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, 'h', 'Guardia', 'ACTIVE')",
                guard, "g-" + guard + "@t.com");
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'M4-L12')", lot, nbh);
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, 'Principal', 'BOTH')",
                gate, nbh);

        // Invitacion activa de un invitado nuevo (sin enrolamiento previo).
        Instant now = Instant.now();
        Instant validFrom = now.minusSeconds(60);
        Instant validUntil = now.plusSeconds(3600);
        jdbc.update("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status,
                    guest_name, guest_document, companions, valid_from, valid_until,
                    max_uses, used_count, token_kid, jti
                ) VALUES (?, ?, ?, ?, 'SINGLE', 'ACTIVE', 'Maria Lopez', '30123456', 2, ?, ?, 1, 0,
                          (SELECT kid FROM signing_keys WHERE neighborhood_id=? AND active=TRUE), ?)
                """, invitation, nbh, lot, owner,
                Timestamp.from(validFrom), Timestamp.from(validUntil), nbh, guestJti);

        String guestToken = qrTokenIssuer.issue(new QrTokenIssuer.IssueCommand(
                nbh, lot, invitation, guestJti, InvitationType.SINGLE, validFrom, validUntil, 1));

        // Permiso WORKER activo: jueves 08:00-18:00 America/Argentina/Mendoza.
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'WORKER',
                        jsonb_build_object(
                            'service',    'Jardineria',
                            'weekdays',   '[4]'::jsonb,
                            'dailyFrom',  '08:00',
                            'dailyUntil', '18:00'),
                        TRUE)
                """, worker, lot);

        HttpClient http = HttpClient.newHttpClient();
        String guardJwt = jwt.issue(guard, nbh, List.of(Role.GUARD), List.of()).token();
        String workerJwt = jwt.issue(worker, nbh, List.of(), List.of()).token();

        // -------- Ramo GUEST --------
        HttpResponse<String> guestResp = validate(http, guardJwt, gate, guard, guestToken, Instant.now());
        System.out.println("=== NEEDS_ENROLLMENT GUEST ===");
        System.out.println("HTTP " + guestResp.statusCode());
        System.out.println(guestResp.body());
        assertThat(guestResp.statusCode()).isEqualTo(200);
        assertThat(guestResp.body()).contains("\"result\":\"NEEDS_ENROLLMENT\"");
        assertThat(guestResp.body()).contains("\"subjectKind\":\"GUEST\"");
        assertThat(guestResp.body()).contains("\"name\":\"Maria Lopez\"");

        // -------- Ramo WORKER --------
        HttpResponse<String> qrResp = http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/me/worker-qr"))
                .header("Authorization", "Bearer " + workerJwt)
                .timeout(Duration.ofSeconds(5))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(qrResp.statusCode()).isEqualTo(200);
        String workerToken = extract(qrResp.body(), "\"token\":\"", "\"");

        Instant thursdayInSlot = Instant.parse("2026-11-05T13:00:00Z");
        HttpResponse<String> workerResp = validate(http, guardJwt, gate, guard, workerToken, thursdayInSlot);
        System.out.println("=== NEEDS_ENROLLMENT WORKER ===");
        System.out.println("HTTP " + workerResp.statusCode());
        System.out.println(workerResp.body());
        assertThat(workerResp.statusCode()).isEqualTo(200);
        assertThat(workerResp.body()).contains("\"result\":\"NEEDS_ENROLLMENT\"");
        assertThat(workerResp.body()).contains("\"subjectKind\":\"WORKER\"");
        assertThat(workerResp.body()).contains("\"name\":\"Juan Jardinero\"");
    }

    private HttpResponse<String> validate(HttpClient http, String guardJwt, UUID gate, UUID guardId,
                                          String token, Instant capturedAt) throws Exception {
        String body = "{\"gateId\":\"" + gate + "\",\"guardId\":\"" + guardId + "\","
                + "\"token\":\"" + token + "\",\"direction\":\"IN\",\"companions\":0,"
                + "\"capturedAt\":\"" + capturedAt + "\",\"clientEventId\":\"" + UUID.randomUUID()
                + "\",\"syncedOffline\":false}";
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/access/validate"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + guardJwt)
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String extract(String json, String prefix, String suffix) {
        int i = json.indexOf(prefix);
        if (i < 0) throw new IllegalStateException("no encontrado " + prefix);
        int start = i + prefix.length();
        return json.substring(start, json.indexOf(suffix, start));
    }
}
