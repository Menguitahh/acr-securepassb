package com.securepass.security;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.invitation.application.SigningKeyIssuer;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke HTTP que confirma que la fuente de verdad de reads de "quien ocupa
 * este lote y con que rol" es lot_permissions.
 *
 * Post-drop de family_members: los casos que verificaban "solo en family_members
 * no aparece" y "el alta escribe en ambas tablas" murieron con la tabla. Sobrevive:
 *   - una fila insertada directo en lot_permissions aparece en el GET (prueba
 *     que el read ruta HTTP -> lot_permissions esta viva)
 *   - alta admin escribe lot_permissions y el read la ve (flujo end-to-end)
 *   - smoke normal owner->invitacion->listar familia
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LotPermissionsReadsSmokeHttpTest {

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
    @Autowired SigningKeyIssuer signingKeyIssuer;

    UUID barrio;
    UUID lot;
    UUID owner;
    UUID directLp;
    HttpClient http;

    @BeforeEach
    void seed() {
        http = HttpClient.newHttpClient();

        barrio = UUID.randomUUID();
        lot = UUID.randomUUID();
        owner = UUID.randomUUID();
        directLp = UUID.randomUUID();

        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'B', ?)",
                barrio, "b-" + UUID.randomUUID());
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'L-1')", lot, barrio);
        signingKeyIssuer.issueForNeighborhood(barrio);

        insertUser(owner, "owner");
        insertUser(directLp, "direct-lp");

        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'OWNER', jsonb_build_object('can_invite', TRUE, 'relation', 'OWNER'), TRUE)
                """, owner, lot);
        jdbc.update("""
                INSERT INTO memberships (user_id, neighborhood_id, role, active)
                VALUES (?, ?, 'RESIDENT_OWNER', TRUE)
                """, owner, barrio);

        // Fila insertada directo en lot_permissions (sin pasar por el flujo
        // de alta): tiene que aparecer en el GET de familia.
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'MEMBER', jsonb_build_object('can_invite', TRUE, 'relation', 'Directo'), TRUE)
                """, directLp, lot);
    }

    @Test
    void fila_directa_en_lot_permissions_aparece_por_HTTP() throws Exception {
        HttpResponse<String> resp = get("/api/v1/family/lots/" + lot + "/members", tokenOwner());
        report("GET family (directa en lot_permissions)", resp);
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains(directLp.toString());
    }

    @Test
    void alta_admin_por_HTTP_escribe_lot_permissions_y_read_la_ve() throws Exception {
        UUID admin = UUID.randomUUID();
        insertUser(admin, "admin");
        jdbc.update("""
                INSERT INTO memberships (user_id, neighborhood_id, role, active)
                VALUES (?, ?, 'ADMIN', TRUE)
                """, admin, barrio);
        String adminToken = jwt.issue(admin, barrio, List.of(Role.ADMIN), List.of()).token();

        UUID lotAlta = UUID.randomUUID();
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'L-ALTA')",
                lotAlta, barrio);

        String email = "nuevo-" + UUID.randomUUID() + "@t.com";
        String dni = String.valueOf(30_000_000 + Math.abs(UUID.randomUUID().hashCode() % 9_000_000));
        String body = """
                {"email":"%s","fullName":"Nuevo Titular","dni":"%s","role":"RESIDENT_OWNER","lotId":"%s"}
                """.formatted(email, dni, lotAlta);

        HttpResponse<String> create = post("/api/v1/admin/users", adminToken, body);
        report("POST /admin/users (alta admin)", create);
        assertThat(create.statusCode()).isEqualTo(201);

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"userId\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(create.body());
        assertThat(m.find()).isTrue();
        UUID nuevoId = UUID.fromString(m.group(1));

        Integer lpCount = jdbc.queryForObject(
                "SELECT count(*) FROM lot_permissions WHERE user_id = ? AND lot_id = ? AND permission_type = 'OWNER'",
                Integer.class, nuevoId, lotAlta);
        assertThat(lpCount).isEqualTo(1);

        String ownerNuevoToken = jwt.issue(
                nuevoId, barrio, List.of(Role.RESIDENT_OWNER), List.of(lotAlta)).token();
        HttpResponse<String> list = get("/api/v1/family/lots/" + lotAlta + "/members", ownerNuevoToken);
        report("GET family (post alta admin)", list);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains(nuevoId.toString());
    }

    @Test
    void smoke_normal_crear_invitacion_y_listar_familia() throws Exception {
        String body = """
                {"lotId":"%s","type":"SINGLE","guestName":"Guest","guestDocument":"30111222",
                 "companions":0,"validFrom":"%s","validUntil":"%s","maxUses":1}
                """.formatted(lot, Instant.now(), Instant.now().plusSeconds(3600));
        HttpResponse<String> create = post("/api/v1/invitations", tokenOwner(), body);
        report("POST /invitations (smoke)", create);
        assertThat(create.statusCode()).isEqualTo(201);

        HttpResponse<String> list = get("/api/v1/family/lots/" + lot + "/members", tokenOwner());
        report("GET family (smoke)", list);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains(owner.toString());
    }

    private String tokenOwner() {
        return jwt.issue(owner, barrio, List.of(Role.RESIDENT_OWNER), List.of(lot)).token();
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(5))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private void report(String label, HttpResponse<String> resp) {
        System.out.println("== " + label + " => HTTP " + resp.statusCode() + " ==");
        System.out.println(resp.body());
        System.out.println();
    }

    private void insertUser(UUID id, String tag) {
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, 'h', ?, 'ACTIVE')
                """, id, tag + "-" + id + "@t.com", tag);
    }
}
