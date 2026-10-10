package com.securepass.modules.neighborhood.infrastructure.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.notification.application.EmailSender;
import com.securepass.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * securepassb#258: el duenio de la plataforma crea barrios y ve cualquiera en
 * modo lectura. Cada admin de barrio sigue viendo solo el suyo.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformAdminHttpTest {

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
    @Autowired PasswordEncoder encoder;
    @MockBean EmailSender emailSender;

    final HttpClient http = HttpClient.newHttpClient();
    UUID platform;
    String platformEmail;
    UUID robles;
    UUID acacias;
    UUID adminRobles;

    @BeforeEach
    void seed() {
        platformEmail = "p-" + UUID.randomUUID() + "@plataforma.com";
        platform = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, ?, 'Duenio', 'ACTIVE')",
                platform, platformEmail, encoder.encode("platform123"));
        jdbc.update("INSERT INTO platform_admins (user_id) VALUES (?)", platform);

        robles = neighborhood("Los Robles " + UUID.randomUUID());
        acacias = neighborhood("Las Acacias " + UUID.randomUUID());
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'R-1')", UUID.randomUUID(), robles);
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'R-2')", UUID.randomUUID(), robles);
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'A-1')", UUID.randomUUID(), acacias);
        adminRobles = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, 'h', 'Admin Robles', 'ACTIVE')",
                adminRobles, "adm-" + adminRobles + "@r.com");
        jdbc.update("INSERT INTO memberships (user_id, neighborhood_id, role, active) VALUES (?, ?, 'ROLE_ADMIN', TRUE)",
                adminRobles, robles);
    }

    private UUID neighborhood(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, ?, ?)", id, name, "n-" + id);
        return id;
    }

    private String platformToken() {
        return jwt.issue(platform, null, List.of(Role.PLATFORM_ADMIN), List.of()).token();
    }

    private String adminRoblesToken() {
        return jwt.issue(adminRobles, robles, List.of(Role.ADMIN), List.of()).token();
    }

    @Test
    void loginDelDuenioTraeElRolSinBarrio() throws Exception {
        HttpResponse<String> resp = send("POST", "/api/v1/auth/login", null, null,
                "{\"identifier\":\"" + platformEmail + "\",\"password\":\"platform123\"}");
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        JsonNode user = json.readTree(resp.body()).get("user");
        assertThat(user.get("roles").toString()).isEqualTo("[\"PLATFORM_ADMIN\"]");
        assertThat(user.get("neighborhoodId").isNull()).isTrue();
    }

    @Test
    void listaTodosLosBarriosConLotesYAdmins() throws Exception {
        HttpResponse<String> resp = send("GET", "/api/v1/platform/neighborhoods", platformToken(), null, null);
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode robl = null;
        List<String> ids = new ArrayList<>();
        for (JsonNode n : json.readTree(resp.body())) {
            ids.add(n.get("id").asText());
            if (n.get("id").asText().equals(robles.toString())) robl = n;
        }
        assertThat(ids).contains(robles.toString(), acacias.toString());
        assertThat(robl.get("lotCount").asLong()).isEqualTo(2);
        assertThat(robl.at("/admins/0/fullName").asText()).isEqualTo("Admin Robles");
    }

    @Test
    void unAdminDeBarrioNoEntraAlPanel() throws Exception {
        assertThat(send("GET", "/api/v1/platform/neighborhoods", adminRoblesToken(), null, null).statusCode())
                .isEqualTo(403);
    }

    @Test
    void creaElBarrioConGaritaClaveYAdminQueRecibeElMail() throws Exception {
        String slug = "nuevo-" + ThreadLocalRandom.current().nextInt(1_000_000);
        String dni = String.valueOf(ThreadLocalRandom.current().nextInt(20_000_000, 49_999_999));
        String body = """
                {"name":"Barrio %s","slug":"%s","timezone":"America/Argentina/Mendoza",
                 "adminEmail":"admin-%s@nuevo.com","adminFullName":"Admin Nuevo","adminDni":"%s"}
                """.formatted(slug, slug, slug, dni);
        HttpResponse<String> resp = send("POST", "/api/v1/platform/neighborhoods", platformToken(), null, body);
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(201);
        UUID id = UUID.fromString(json.readTree(resp.body()).get("neighborhoodId").asText());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM gates WHERE neighborhood_id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM signing_keys WHERE neighborhood_id = ? AND active", Integer.class, id))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT u.status FROM memberships m JOIN users u ON u.id = m.user_id
                 WHERE m.neighborhood_id = ? AND m.role = 'ROLE_ADMIN'
                """, String.class, id)).isEqualTo("PENDING_ACTIVATION");
        ArgumentCaptor<EmailSender.EmailMessage> mail = ArgumentCaptor.forClass(EmailSender.EmailMessage.class);
        verify(emailSender, timeout(3000)).send(mail.capture());
        assertThat(mail.getValue().to()).isEqualTo("admin-" + slug + "@nuevo.com");
        assertThat(mail.getValue().textBody()).contains("Duenio");

        // Mismo slug: 409 que dice que dato esta tomado.
        HttpResponse<String> again = send("POST", "/api/v1/platform/neighborhoods", platformToken(), null,
                body.replace("admin-" + slug, "otro-" + slug).replace(dni, "30000999").replace("Barrio " + slug, "Otro " + slug));
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(json.readTree(again.body()).get("code").asText()).isEqualTo("SLUG_TAKEN");

        // Email del admin ya usado.
        HttpResponse<String> email = send("POST", "/api/v1/platform/neighborhoods", platformToken(), null,
                body.replace(slug + "\",\"timezone", slug + "-b\",\"timezone").replace("Barrio " + slug, "Tercero " + slug));
        assertThat(email.statusCode()).as(email.body()).isEqualTo(409);
        assertThat(json.readTree(email.body()).get("code").asText()).isEqualTo("EMAIL_TAKEN");
    }

    @Test
    void verUnBarrioEsLeerComoSuAdminYNoEscribir() throws Exception {
        HttpResponse<String> lots = send("GET", "/api/v1/admin/lots?page=0", platformToken(), robles, null);
        assertThat(lots.statusCode()).as(lots.body()).isEqualTo(200);
        List<String> codes = new ArrayList<>();
        json.readTree(lots.body()).forEach(l -> codes.add(l.get("code").asText()));
        assertThat(codes).containsExactly("R-1", "R-2");

        assertThat(send("POST", "/api/v1/admin/lots", platformToken(), robles, "{\"code\":\"R-9\"}").statusCode())
                .isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lots WHERE neighborhood_id = ?", Integer.class, robles))
                .isEqualTo(2);
        // Sin elegir barrio no es admin de ninguno.
        assertThat(send("GET", "/api/v1/admin/lots?page=0", platformToken(), null, null).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/api/v1/admin/lots?page=0", platformToken(), null, null, "no-es-uuid").statusCode())
                .isEqualTo(400);
    }

    @Test
    void elHeaderNoLeSirveAUnAdminDeBarrio() throws Exception {
        HttpResponse<String> lots = send("GET", "/api/v1/admin/lots?page=0", adminRoblesToken(), acacias, null);
        assertThat(lots.statusCode()).isEqualTo(200);
        List<String> codes = new ArrayList<>();
        json.readTree(lots.body()).forEach(l -> codes.add(l.get("code").asText()));
        assertThat(codes).containsExactly("R-1", "R-2");
    }

    private HttpResponse<String> send(String method, String path, String token, UUID viewing, String body)
            throws Exception {
        return send(method, path, token, viewing, body, viewing == null ? null : viewing.toString());
    }

    private HttpResponse<String> send(String method, String path, String token, UUID viewing, String body,
                                      String header) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (header != null) b.header("X-Neighborhood-Id", header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
