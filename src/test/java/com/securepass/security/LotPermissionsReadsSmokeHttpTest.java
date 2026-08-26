package com.securepass.security;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.invitation.application.SigningKeyIssuer;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WBS-108 PR1: smoke HTTP que confirma que la fuente de verdad de READS
 * de family_members es lot_permissions.
 *
 * Los outputs crudos (status + body) los pego en el PR.
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
    @Autowired JwtService jwt;
    @Autowired SigningKeyIssuer signingKeyIssuer;

    UUID barrio;
    UUID lot;
    UUID owner;
    UUID onlyInLotPermissions;
    UUID onlyInFamilyMembers;
    HttpClient http;

    @BeforeEach
    void seed() {
        http = HttpClient.newHttpClient();

        barrio = UUID.randomUUID();
        lot = UUID.randomUUID();
        owner = UUID.randomUUID();
        onlyInLotPermissions = UUID.randomUUID();
        onlyInFamilyMembers = UUID.randomUUID();

        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'B', ?)",
                barrio, "b-" + UUID.randomUUID());
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'L-1')", lot, barrio);
        signingKeyIssuer.issueForNeighborhood(barrio);

        insertUser(owner, "owner");
        insertUser(onlyInLotPermissions, "only-lp");
        insertUser(onlyInFamilyMembers, "only-fm");

        // owner: dual write completo (fila valida en ambas tablas)
        jdbc.update("""
                INSERT INTO family_members (lot_id, user_id, relation, is_owner, can_invite, active)
                VALUES (?, ?, 'OWNER', TRUE, TRUE, TRUE)
                """, lot, owner);
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'OWNER', jsonb_build_object('can_invite', TRUE, 'relation', 'OWNER'), TRUE)
                """, owner, lot);
        jdbc.update("""
                INSERT INTO memberships (user_id, neighborhood_id, role, active)
                VALUES (?, ?, 'RESIDENT_OWNER', TRUE)
                """, owner, barrio);

        // SOLO en lot_permissions: DEBE aparecer en el listado
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'MEMBER', jsonb_build_object('can_invite', TRUE, 'relation', 'Solo LP'), TRUE)
                """, onlyInLotPermissions, lot);

        // SOLO en family_members: NO debe aparecer en el listado
        jdbc.update("""
                INSERT INTO family_members (lot_id, user_id, relation, is_owner, can_invite, active)
                VALUES (?, ?, 'Solo FM', FALSE, TRUE, TRUE)
                """, lot, onlyInFamilyMembers);
    }

    @Test
    void fila_solo_en_lot_permissions_aparece_por_HTTP() throws Exception {
        // Guard: confirmar que la fila es huerfana de verdad (sin espejo en family_members).
        // Si el fixture arrastrara un espejo por dual write, el test pasaria sin probar nada.
        Integer mirror = jdbc.queryForObject(
                "SELECT count(*) FROM family_members WHERE user_id = ? AND lot_id = ?",
                Integer.class, onlyInLotPermissions, lot);
        Integer lpRow = jdbc.queryForObject(
                "SELECT count(*) FROM lot_permissions WHERE user_id = ? AND lot_id = ?",
                Integer.class, onlyInLotPermissions, lot);
        System.out.println("== SQL orphan check ==");
        System.out.println("family_members(only-lp, lot) = " + mirror);
        System.out.println("lot_permissions(only-lp, lot) = " + lpRow);
        System.out.println();
        assertThat(mirror).isZero();
        assertThat(lpRow).isEqualTo(1);

        HttpResponse<String> resp = get("/api/v1/family/lots/" + lot + "/members", tokenOwner());
        report("GET family (SOLO lot_permissions)", resp);
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains(onlyInLotPermissions.toString());
    }

    @Test
    void alta_admin_por_HTTP_escribe_en_ambas_tablas_y_read_sale_de_lot_permissions() throws Exception {
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

        // Dual write: la alta admin debe dejar fila en las DOS tablas.
        Integer fmCount = jdbc.queryForObject(
                "SELECT count(*) FROM family_members WHERE user_id = ? AND lot_id = ?",
                Integer.class, nuevoId, lotAlta);
        Integer lpCount = jdbc.queryForObject(
                "SELECT count(*) FROM lot_permissions WHERE user_id = ? AND lot_id = ? AND permission_type = 'OWNER'",
                Integer.class, nuevoId, lotAlta);
        System.out.println("== SQL dual write check ==");
        System.out.println("family_members(nuevo, lot) = " + fmCount);
        System.out.println("lot_permissions(nuevo, lot, OWNER) = " + lpCount);
        System.out.println();
        assertThat(fmCount).isEqualTo(1);
        assertThat(lpCount).isEqualTo(1);

        // Read desde lot_permissions: el nuevo owner aparece en el listado.
        String ownerNuevoToken = jwt.issue(
                nuevoId, barrio, List.of(Role.RESIDENT_OWNER), List.of(lotAlta)).token();
        HttpResponse<String> list = get("/api/v1/family/lots/" + lotAlta + "/members", ownerNuevoToken);
        report("GET family (post alta admin)", list);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains(nuevoId.toString());
    }

    @Test
    void fila_solo_en_family_members_NO_aparece_por_HTTP() throws Exception {
        HttpResponse<String> resp = get("/api/v1/family/lots/" + lot + "/members", tokenOwner());
        report("GET family (SOLO family_members)", resp);
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).doesNotContain(onlyInFamilyMembers.toString());
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
