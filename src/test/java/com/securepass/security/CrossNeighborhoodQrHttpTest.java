package com.securepass.security;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.invitation.application.QrTokenIssuer;
import com.securepass.modules.invitation.application.SigningKeyIssuer;
import com.securepass.modules.invitation.domain.InvitationType;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #211: un QR valido del barrio B no sirve en una garita del barrio A, por
 * ningun camino que reciba un token (validate online, offline y egreso, sync,
 * enroll y sync de enroll, invitado y trabajador). La firma es real (clave de
 * B), el pase esta vigente y la persona enrolada en B: lo unico que falla es
 * el barrio. Ningun camino puede permitir, enrolar, pedir enrolamiento,
 * devolver datos de B, consumir el pase de B ni dejar en A un log que apunte
 * a B.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CrossNeighborhoodQrHttpTest {

    // Jueves 10:00 en Mendoza: dentro de la franja del trabajador.
    private static final Instant CAPTURED_AT = Instant.parse("2026-11-05T13:00:00Z");

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
    @Autowired SigningKeyIssuer signingKeys;
    @Autowired QrTokenIssuer qrTokenIssuer;

    final HttpClient http = HttpClient.newHttpClient();
    UUID barrioA;
    UUID gateA;
    UUID guardA;
    UUID barrioB;
    UUID lotB;
    UUID invitationB;
    UUID workerB;
    String guestTokenB;
    // users.document_number es unico: un DNI por test.
    String guestDni;
    String workerDni;

    @BeforeEach
    void seed() {
        barrioA = UUID.randomUUID();
        gateA = UUID.randomUUID();
        guardA = UUID.randomUUID();
        barrioB = UUID.randomUUID();
        lotB = UUID.randomUUID();
        invitationB = UUID.randomUUID();
        workerB = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();
        UUID jti = UUID.randomUUID();
        guestDni = String.valueOf(30_000_000 + ThreadLocalRandom.current().nextInt(9_000_000));
        workerDni = String.valueOf(40_000_000 + ThreadLocalRandom.current().nextInt(9_000_000));

        for (UUID n : List.of(barrioA, barrioB)) {
            jdbc.update("INSERT INTO neighborhoods (id, name, slug, timezone) VALUES (?, 'B', ?, 'America/Argentina/Mendoza')",
                    n, "b-" + UUID.randomUUID());
            signingKeys.issueForNeighborhood(n);
        }
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, 'Garita A', 'BOTH')",
                gateA, barrioA);
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, 'h', 'Guardia A', 'ACTIVE')",
                guardA, "g-" + guardA + "@t.com");
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, 'h', 'Titular B', 'ACTIVE')",
                ownerB, "o-" + ownerB + "@t.com");
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'B-LOTE-7')", lotB, barrioB);

        Instant validFrom = CAPTURED_AT.minusSeconds(3600);
        Instant validUntil = CAPTURED_AT.plusSeconds(3600);
        jdbc.update("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status,
                    guest_name, guest_document, companions, valid_from, valid_until,
                    max_uses, used_count, token_kid, jti
                ) VALUES (?, ?, ?, ?, 'SINGLE', 'ACTIVE', 'Invitado B', ?, 0, ?, ?, 1, 0,
                          (SELECT kid FROM signing_keys WHERE neighborhood_id=? AND active=TRUE), ?)
                """, invitationB, barrioB, lotB, ownerB, guestDni, Timestamp.from(validFrom),
                Timestamp.from(validUntil), barrioB, jti);
        guestTokenB = qrTokenIssuer.issue(new QrTokenIssuer.IssueCommand(
                barrioB, lotB, invitationB, jti, InvitationType.SINGLE, validFrom, validUntil, 1));

        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, document_number, status)
                VALUES (?, ?, 'h', 'Trabajador B', ?, 'ACTIVE')
                """, workerB, "w-" + workerB + "@t.com", workerDni);
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'WORKER', jsonb_build_object(
                    'service', 'Jardineria', 'weekdays', '[1,2,3,4,5,6,7]'::jsonb,
                    'dailyFrom', '00:00', 'dailyUntil', '23:59'), TRUE)
                """, workerB, lotB);

        // Los dos enrolados en B: sin esto el online pediria enrolamiento y no
        // llegaria al caso mas grave (ALLOWED).
        for (String dni : List.of(guestDni, workerDni)) {
            jdbc.update("INSERT INTO guest_enrollments (id, neighborhood_id, document_number) VALUES (?, ?, ?)",
                    UUID.randomUUID(), barrioB, dni);
        }
    }

    private String workerTokenB() throws Exception {
        String workerJwt = jwt.issue(workerB, barrioB, List.of(), List.of()).token();
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/me/worker-qr"))
                .header("Authorization", "Bearer " + workerJwt)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        String prefix = "\"token\":\"";
        int start = resp.body().indexOf(prefix) + prefix.length();
        return resp.body().substring(start, resp.body().indexOf('"', start));
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        String guardJwt = jwt.issue(guardA, barrioA, List.of(Role.GUARD), List.of()).token();
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/access/" + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + guardJwt)
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> validate(String token, String direction, boolean offline) throws Exception {
        return post("validate", "{\"gateId\":\"" + gateA + "\",\"guardId\":\"" + guardA + "\","
                + "\"token\":\"" + token + "\",\"direction\":\"" + direction + "\",\"companions\":0,"
                + "\"capturedAt\":\"" + CAPTURED_AT + "\",\"clientEventId\":\"" + UUID.randomUUID()
                + "\",\"syncedOffline\":" + offline + "}");
    }

    private HttpResponse<String> enroll(String token, String dni) throws Exception {
        return post("enroll", "{\"token\":\"" + token + "\",\"documentNumber\":\"" + dni + "\","
                + "\"gateId\":\"" + gateA + "\",\"clientEventId\":\"" + UUID.randomUUID() + "\","
                + "\"capturedAt\":\"" + CAPTURED_AT + "\"}");
    }

    private HttpResponse<String> sync(String token, String dni) throws Exception {
        String event = "{\"clientEventId\":\"" + UUID.randomUUID() + "\",\"token\":\"" + token + "\","
                + "\"gateId\":\"" + gateA + "\",\"direction\":\"IN\",\"companions\":0,"
                + "\"capturedAt\":\"" + CAPTURED_AT + "\",\"localDecision\":\"ALLOWED\""
                + (dni == null ? "" : ",\"documentNumber\":\"" + dni + "\"") + "}";
        return post("sync", "{\"deviceId\":\"" + UUID.randomUUID() + "\",\"events\":[" + event + "]}");
    }

    private void assertNoCruza(HttpResponse<String> resp) {
        System.out.println("HTTP " + resp.statusCode() + " " + resp.body());
        assertThat(resp.body())
                .doesNotContain("ALLOWED")
                .doesNotContain("ENROLLED")
                .doesNotContain("NEEDS_ENROLLMENT")
                .doesNotContain("Invitado B")
                .doesNotContain("Trabajador B")
                .doesNotContain("B-LOTE-7");
        assertThat(jdbc.queryForObject("SELECT used_count FROM invitations WHERE id = ?", Integer.class, invitationB))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM access_logs WHERE neighborhood_id = ? AND (lot_id = ? OR invitation_id = ?)",
                Integer.class, barrioA, lotB, invitationB)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM access_logs WHERE neighborhood_id = ? AND result = 'ALLOWED'",
                Integer.class, barrioA)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM guest_enrollments WHERE neighborhood_id = ?", Integer.class, barrioA)).isZero();
    }

    @Test
    void validateOnline() throws Exception {
        assertNoCruza(validate(guestTokenB, "IN", false));
    }

    @Test
    void validateOffline() throws Exception {
        assertNoCruza(validate(guestTokenB, "IN", true));
    }

    @Test
    void validateEgreso() throws Exception {
        assertNoCruza(validate(guestTokenB, "OUT", false));
    }

    @Test
    void syncDeAcceso() throws Exception {
        assertNoCruza(sync(guestTokenB, null));
    }

    @Test
    void enrollInvitado() throws Exception {
        assertNoCruza(enroll(guestTokenB, guestDni));
    }

    @Test
    void syncDeEnrollInvitado() throws Exception {
        assertNoCruza(sync(guestTokenB, guestDni));
    }

    @Test
    void validateTrabajador() throws Exception {
        assertNoCruza(validate(workerTokenB(), "IN", false));
    }

    @Test
    void enrollTrabajador() throws Exception {
        assertNoCruza(enroll(workerTokenB(), workerDni));
    }
}
