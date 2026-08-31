package com.securepass.smoke;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.invitation.application.SigningKeyIssuer;
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
 * WBS-117 smoke: recorrido HTTP completo del QR del trabajador contra Postgres
 * real, con firma Ed25519 real, parseo SP1 real, y verificacion del payload
 * real. Los tests unitarios mockean signing keys y worker_tokens; este cubre
 * lo que ellos no.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkerValidateHttpSmoke {

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

    @Test
    void workerQrValidateAllowedYOutOfScheduleYSyncEndpoint() throws Exception {
        UUID nbh = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID worker = UUID.randomUUID();
        UUID guardId = UUID.randomUUID();
        UUID lot = UUID.randomUUID();
        UUID gate = UUID.randomUUID();
        String dni = "3" + (10_000_000 + (int)(Math.random() * 89_999_999));

        // Seed: barrio (TZ Mendoza por default), signing key, users, lot, gate.
        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'Smoke', ?)",
                nbh, "smoke-" + UUID.randomUUID());
        signingKeys.issueForNeighborhood(nbh);
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, 'h', 'Titular', 'ACTIVE')",
                owner, "o-" + owner + "@t.com");
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, document_number, status) VALUES (?, ?, 'h', 'Juan Jardinero', ?, 'ACTIVE')",
                worker, "w-" + worker + "@t.com", dni);
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, 'h', 'Guardia', 'ACTIVE')",
                guardId, "g-" + guardId + "@t.com");
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, ?)",
                lot, nbh, "L-" + UUID.randomUUID().toString().substring(0, 6));
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, 'Principal', 'BOTH')",
                gate, nbh);
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
        String workerJwt = jwt.issue(worker, nbh, List.of(), List.of()).token();
        String guardJwt = jwt.issue(guardId, nbh, List.of(Role.GUARD), List.of()).token();

        // Paso 1: worker pide su QR permanente.
        HttpResponse<String> qrResp = http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/me/worker-qr"))
                .header("Authorization", "Bearer " + workerJwt)
                .timeout(Duration.ofSeconds(5))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        System.out.println("=== GET /me/worker-qr ===");
        System.out.println("HTTP " + qrResp.statusCode());
        System.out.println(qrResp.body());
        assertThat(qrResp.statusCode()).isEqualTo(200);
        String token = extractString(qrResp.body(), "\"token\":\"", "\"");

        // WBS-122: la validacion ahora exige enrolamiento previo por persona.
        // Sembramos el enrolamiento (equivalente al primer POST /access/enroll)
        // para poder afirmar el camino ALLOWED_WORKER en este smoke, que existe
        // para cubrir la firma Ed25519 real + parseo SP1 real. El ramo
        // NEEDS_ENROLLMENT.WORKER esta cubierto por EnrollGuestUseCaseTest y
        // el smoke HTTP end-to-end de WBS-122.
        jdbc.update("""
                INSERT INTO guest_enrollments
                    (neighborhood_id, invitation_id, document_number, enrolled_by, gate_id)
                VALUES (?, NULL, ?, ?, ?)
                """, nbh, dni, guardId, gate);

        // Paso 2: validate dentro de franja (jueves 2026-11-05 10:00 Mza = 13:00Z).
        Instant inSlot = Instant.parse("2026-11-05T13:00:00Z");
        HttpResponse<String> allowed = validate(http, guardJwt, gate, guardId, token, inSlot);
        System.out.println("=== POST /access/validate (dentro de franja) ===");
        System.out.println("HTTP " + allowed.statusCode());
        System.out.println(allowed.body());
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.body()).contains("\"result\":\"ALLOWED_WORKER\"");
        assertThat(allowed.body()).contains("Juan Jardinero");
        assertThat(allowed.body()).contains("Jardineria");

        // Paso 3: validate fuera de franja (miercoles 2026-11-04 mismo horario).
        Instant outSlot = Instant.parse("2026-11-04T13:00:00Z");
        HttpResponse<String> denied = validate(http, guardJwt, gate, guardId, token, outSlot);
        System.out.println("=== POST /access/validate (fuera de franja) ===");
        System.out.println("HTTP " + denied.statusCode());
        System.out.println(denied.body());
        assertThat(denied.statusCode()).isEqualTo(200);
        assertThat(denied.body()).contains("\"result\":\"DENIED\"");
        assertThat(denied.body()).contains("\"reason\":\"OUT_OF_SCHEDULE\"");

        // Paso 4: sync endpoint.
        HttpResponse<String> sync = http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/access/permissions/sync"))
                .header("Authorization", "Bearer " + guardJwt)
                .timeout(Duration.ofSeconds(5))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        System.out.println("=== GET /access/permissions/sync ===");
        System.out.println("HTTP " + sync.statusCode());
        System.out.println(sync.body());
        assertThat(sync.statusCode()).isEqualTo(200);
        assertThat(sync.body()).contains("Jardineria");
        assertThat(sync.body()).contains("\"dailyFrom\":\"08:00\"");
        assertThat(sync.body()).contains("\"dailyUntil\":\"18:00\"");
        // WBS-118 #149: la tz viaja en el shape, en la misma fila que la franja
        // (mismo motivo: "martes de 8 a 12" esta definido en la zona del barrio).
        assertThat(sync.body()).contains("\"timezone\":\"America/Argentina/Mendoza\"");
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

    private static String extractString(String json, String prefix, String suffix) {
        int i = json.indexOf(prefix);
        if (i < 0) throw new IllegalStateException("no encontrado " + prefix + " en " + json);
        int start = i + prefix.length();
        int end = json.indexOf(suffix, start);
        return json.substring(start, end);
    }
}
