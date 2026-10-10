package com.securepass.security;

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
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WBS-115: contrato de POST /api/v1/lots/{lotId}/workers.
 *
 * Cinco casos de validacion end-to-end: si algo asi cae hasta ScheduleWindow
 * o WorkerPermission via el compact constructor, el ExceptionHandler devuelve
 * 500 y meteria basura en lot_permissions.attributes que despues rompe
 * /access/validate en runtime. Jakarta corta el request en 400.
 *
 * Tambien: 403 sobre lote ajeno para confirmar el gate de OWNER.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AddWorkerHttpTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenProvider jwt;

    UUID barrio;
    UUID lot;
    UUID otherLot;
    UUID owner;
    HttpClient http;

    @BeforeEach
    void seed() {
        http = HttpClient.newHttpClient();
        barrio = UUID.randomUUID();
        lot = UUID.randomUUID();
        otherLot = UUID.randomUUID();
        owner = UUID.randomUUID();

        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'B', ?)",
                barrio, "b-" + UUID.randomUUID());
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, ?)",
                lot, barrio, "L-" + UUID.randomUUID().toString().substring(0, 6));
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, ?)",
                otherLot, barrio, "L-" + UUID.randomUUID().toString().substring(0, 6));
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, 'h', 'owner', 'ACTIVE')
                """, owner, "o-" + owner + "@t.com");
        jdbc.update("""
                INSERT INTO memberships (user_id, neighborhood_id, role, active)
                VALUES (?, ?, 'ROLE_RESIDENT_OWNER', TRUE)
                """, owner, barrio);
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'OWNER', '{}'::jsonb, TRUE)
                """, owner, lot);
        // Sin OWNER en otherLot: el mismo titular no puede administrar workers alli.
    }

    private String token() {
        return jwt.issue(owner, barrio, List.of(Role.RESIDENT_OWNER), List.of(lot)).token();
    }

    private HttpResponse<String> post(UUID targetLot, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/lots/" + targetLot + "/workers"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(5))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** Devuelve el body con sustituciones raw (literales JSON) para los campos indicados. */
    private static String body(String service, String weekdays, String dailyFrom, String dailyUntil) {
        return "{\"fullName\":\"Juan\",\"dni\":\"30111222\",\"email\":\"j@dev.local\","
                + "\"service\":" + service + ","
                + "\"weekdays\":" + weekdays + ","
                + "\"dailyFrom\":" + dailyFrom + ","
                + "\"dailyUntil\":" + dailyUntil + "}";
    }

    private static String validBody() {
        return body("\"Jardineria\"", "[2,4]", "\"08:00\"", "\"12:00\"");
    }

    // securepassf#119: email o telefono; sin ninguno, 400.
    @Test
    void sinEmailNiTelefonoDevuelve400() throws Exception {
        HttpResponse<String> resp = post(lot, validBody().replace("\"email\":\"j@dev.local\",", ""));
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("validation-error").doesNotContain("internal-error");
    }

    @Test
    void soloConTelefonoCreaElTrabajador() throws Exception {
        String dni = String.valueOf(20_000_000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(9_000_000));
        HttpResponse<String> resp = post(lot,
                validBody().replace("30111222", dni).replace("\"email\":\"j@dev.local\",", "\"phone\":\"2615550000\","));
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT phone FROM users WHERE document_number = ?", String.class, dni))
                .isEqualTo("2615550000");
    }

    @Test
    void weekdaysVacioDevuelve400() throws Exception {
        HttpResponse<String> resp = post(lot, body("\"Jardineria\"", "[]", "\"08:00\"", "\"12:00\""));
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("validation-error").contains("weekdays");
        assertThat(resp.body()).doesNotContain("internal-error");
    }

    @Test
    void weekdaysFueraDeRangoDevuelve400() throws Exception {
        HttpResponse<String> resp = post(lot, body("\"Jardineria\"", "[8]", "\"08:00\"", "\"12:00\""));
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("validation-error").contains("weekdays");
        assertThat(resp.body()).doesNotContain("internal-error");
    }

    @Test
    void dailyUntilMenorIgualDailyFromDevuelve400() throws Exception {
        HttpResponse<String> resp = post(lot, body("\"Jardineria\"", "[2,4]", "\"08:00\"", "\"08:00\""));
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("validation-error");
        assertThat(resp.body()).contains("posterior a dailyFrom");
        assertThat(resp.body()).doesNotContain("internal-error");
    }

    @Test
    void serviceVacioDevuelve400() throws Exception {
        HttpResponse<String> resp = post(lot, body("\"\"", "[2,4]", "\"08:00\"", "\"12:00\""));
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("validation-error").contains("service");
        assertThat(resp.body()).doesNotContain("internal-error");
    }

    @Test
    void serviceDe81CaracteresDevuelve400() throws Exception {
        String big = "\"" + "x".repeat(81) + "\"";
        HttpResponse<String> resp = post(lot, body(big, "[2,4]", "\"08:00\"", "\"12:00\""));
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("validation-error").contains("service");
        assertThat(resp.body()).doesNotContain("internal-error");
    }

    @Test
    void tituLarSobreLoteAjenoDevuelve403() throws Exception {
        HttpResponse<String> resp = post(otherLot, validBody());
        assertThat(resp.statusCode()).isEqualTo(403);
        assertThat(resp.body()).contains("forbidden");
    }
}
