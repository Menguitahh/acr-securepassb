package com.securepass.modules.identity.infrastructure.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securepass.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #179: lots[] de login y refresh trae los lotes de trabajador con
 * {@code kind}, servicio y franja. El JWT sigue llevando solo los lotes de
 * residente: un lote WORKER no da acceso de residente a esa casa.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginLotsHttpTest {

    private static final String PASSWORD = "secret123";

    // Login y refresh usan Redis (rate limit y refresh tokens). Sin esto el
    // test pasa solo si hay un Redis local en 6379.
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder encoder;

    UUID barrio;
    UUID casaPropia;
    UUID casaDondeTrabaja;
    String email;

    // Jardinero que vive en el barrio: OWNER en A-1, WORKER en A-14.
    @BeforeEach
    void seed() {
        barrio = UUID.randomUUID();
        casaPropia = UUID.randomUUID();
        casaDondeTrabaja = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        email = "j-" + user + "@t.com";
        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'B', ?)", barrio, "b-" + barrio);
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'A-1')", casaPropia, barrio);
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'A-14')", casaDondeTrabaja, barrio);
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, ?, 'Juan', 'ACTIVE')
                """, user, email, encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO memberships (user_id, neighborhood_id, role) VALUES (?, ?, 'ROLE_RESIDENT_OWNER')",
                user, barrio);
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'OWNER', jsonb_build_object('can_invite', true), TRUE)
                """, user, casaPropia);
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'WORKER', jsonb_build_object(
                    'service', 'Jardineria', 'weekdays', '[1,3,5]'::jsonb,
                    'dailyFrom', '08:00', 'dailyUntil', '12:00'), TRUE)
                """, user, casaDondeTrabaja);
    }

    private HttpResponse<String> send(String method, String path, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode login() throws Exception {
        HttpResponse<String> resp = send("POST", "/api/v1/auth/login",
                "{\"identifier\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}", null);
        assertThat(resp.statusCode()).as("login: %s", resp.body()).isEqualTo(200);
        return json.readTree(resp.body());
    }

    private List<String> jwtLots(String accessToken) throws Exception {
        String payload = new String(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        json.readTree(payload).get("lots").forEach(n -> out.add(n.asText()));
        return out;
    }

    private void assertLots(JsonNode lots) {
        assertThat(lots).hasSize(2);
        JsonNode propia = lots.get(0);
        assertThat(propia.get("code").asText()).isEqualTo("A-1");
        assertThat(propia.get("kind").asText()).isEqualTo("OWNER");
        assertThat(propia.get("isOwner").asBoolean()).isTrue();
        assertThat(propia.get("canInvite").asBoolean()).isTrue();
        assertThat(propia.has("service")).isFalse();
        assertThat(propia.has("schedule")).isFalse();

        JsonNode trabajo = lots.get(1);
        assertThat(trabajo.get("code").asText()).isEqualTo("A-14");
        assertThat(trabajo.get("kind").asText()).isEqualTo("WORKER");
        assertThat(trabajo.get("isOwner").asBoolean()).isFalse();
        assertThat(trabajo.get("canInvite").asBoolean()).isFalse();
        assertThat(trabajo.get("service").asText()).isEqualTo("Jardineria");
        JsonNode schedule = trabajo.get("schedule");
        assertThat(schedule.get("weekdays").toString()).isEqualTo("[1,3,5]");
        assertThat(schedule.get("dailyFrom").asText()).isEqualTo("08:00");
        assertThat(schedule.get("dailyUntil").asText()).isEqualTo("12:00");
    }

    @Test
    void loginTraeLosLotesDeTrabajadorConKindServicioYFranja() throws Exception {
        assertLots(login().get("user").get("lots"));
    }

    @Test
    void elJwtSoloLlevaLosLotesDeResidente() throws Exception {
        String access = login().get("accessToken").asText();

        assertThat(jwtLots(access)).containsExactly(casaPropia.toString());
        // Efecto observable: la casa donde trabaja no le abre la bitacora.
        assertThat(send("GET", "/api/v1/access-logs?lotId=" + casaDondeTrabaja, null, access).statusCode())
                .isEqualTo(403);
        assertThat(send("GET", "/api/v1/access-logs?lotId=" + casaPropia, null, access).statusCode())
                .isEqualTo(200);
    }

    @Test
    void refreshMantieneLotesYJwt() throws Exception {
        String refreshToken = login().get("refreshToken").asText();

        HttpResponse<String> resp = send("POST", "/api/v1/auth/refresh",
                "{\"refreshToken\":\"" + refreshToken + "\"}", null);

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(resp.body());
        assertLots(body.get("user").get("lots"));
        assertThat(jwtLots(body.get("accessToken").asText())).containsExactly(casaPropia.toString());
    }

    // US-65: trabajador sin membresia de barrio (el alta de WORKER no la
    // crea). Antes el login devolvia neighborhoodId null y la app rechazaba
    // la sesion: el trabajador no podia ver su QR.
    @Test
    void trabajadorSinMembresiaEntraConElBarrioDondeTrabaja() throws Exception {
        UUID worker = UUID.randomUUID();
        String workerEmail = "w-" + worker + "@t.com";
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, ?, 'Wanda', 'ACTIVE')
                """, worker, workerEmail, encoder.encode(PASSWORD));
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'WORKER', jsonb_build_object(
                    'service', 'Limpieza', 'weekdays', '[2]'::jsonb,
                    'dailyFrom', '09:00', 'dailyUntil', '13:00'), TRUE)
                """, worker, casaDondeTrabaja);

        HttpResponse<String> resp = send("POST", "/api/v1/auth/login",
                "{\"identifier\":\"" + workerEmail + "\",\"password\":\"" + PASSWORD + "\"}", null);

        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        JsonNode user = json.readTree(resp.body()).get("user");
        assertThat(user.get("neighborhoodId").asText()).isEqualTo(barrio.toString());
        assertThat(user.get("roles")).isEmpty();
        assertThat(user.get("lots").get(0).get("kind").asText()).isEqualTo("WORKER");
    }
}
