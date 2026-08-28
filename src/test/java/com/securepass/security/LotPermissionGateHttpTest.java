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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WBS-108 PR2 tramo 2: cobertura del gate (@PreAuthorize + LotPermissionEvaluator)
 * mas alla del baseline IDOR HTTP. El baseline verifica "residente del lote A no
 * puede tocar recursos del lote B"; este suite verifica dos escenarios que el
 * gate ahora deberia cerrar en el propio SpEL, antes de llegar al use case:
 *
 *   1) Residente sin NINGUN permiso sobre el lote (JWT con rol residente pero
 *      cero filas en lot_permissions para ese user+lote) -> 4xx.
 *   2) Residente con permiso MEMBER intentando una accion que pide OWNER
 *      (extend, revoke, agregar/actualizar familiar) -> 4xx.
 *
 * Como el baseline, no se atornilla el status code exacto (403 vs 404 depende
 * de la ruta): la assertion es "no 2xx".
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LotPermissionGateHttpTest {

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
    UUID gate;
    UUID lot;
    UUID owner;
    UUID member;      // permiso MEMBER sobre lot
    UUID orphan;      // rol residente pero cero filas en lot_permissions
    UUID invitation;
    HttpClient http;

    @BeforeEach
    void seed() {
        http = HttpClient.newHttpClient();

        barrio = UUID.randomUUID();
        gate = UUID.randomUUID();
        lot = UUID.randomUUID();
        owner = UUID.randomUUID();
        member = UUID.randomUUID();
        orphan = UUID.randomUUID();

        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'X', ?)",
                barrio, "n-" + UUID.randomUUID());
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, 'g', 'BOTH')",
                gate, barrio);
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'A-1')", lot, barrio);
        signingKeyIssuer.issueForNeighborhood(barrio);

        insertUser(owner);
        insertUser(member);
        insertUser(orphan);

        // owner del lote
        jdbc.update("""
                INSERT INTO family_members (lot_id, user_id, relation, is_owner, can_invite, active)
                VALUES (?, ?, 'OWNER', TRUE, TRUE, TRUE)
                """, lot, owner);
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'OWNER', jsonb_build_object('can_invite', TRUE, 'relation', 'OWNER'), TRUE)
                """, owner, lot);

        // member con permiso MEMBER (no OWNER)
        jdbc.update("""
                INSERT INTO family_members (lot_id, user_id, relation, is_owner, can_invite, active)
                VALUES (?, ?, 'Hijo/a', FALSE, TRUE, TRUE)
                """, lot, member);
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'MEMBER', jsonb_build_object('can_invite', TRUE, 'relation', 'Hijo/a'), TRUE)
                """, member, lot);

        // orphan: rol residente en el JWT (abajo) pero cero filas en lot_permissions.

        // Invitacion activa creada por owner
        invitation = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status,
                    guest_name, guest_document, companions, valid_from, valid_until,
                    max_uses, used_count, token_kid, jti
                ) VALUES (?, ?, ?, ?, 'SINGLE', 'ACTIVE', 'Maria', '30123456', 0, ?, ?, 1, 0, 'kid', ?)
                """, invitation, barrio, lot, owner,
                Timestamp.from(now.minusSeconds(60)),
                Timestamp.from(now.plusSeconds(3600)), UUID.randomUUID());
    }

    private String tokenMember() {
        // JWT dice RESIDENT_MEMBER, DB dice permiso MEMBER sobre lot.
        return jwt.issue(member, barrio, List.of(Role.RESIDENT_MEMBER), List.of(lot)).token();
    }

    private String tokenOrphan() {
        // JWT dice RESIDENT_MEMBER (pasa el hasRole en gates viejos), pero DB no
        // tiene lot_permissions para orphan+lot: el gate nuevo debe rechazar.
        return jwt.issue(orphan, barrio, List.of(Role.RESIDENT_MEMBER), List.of(lot)).token();
    }

    // ---------------- MEMBER -> accion OWNER ----------------

    @Test
    void member_no_puede_extend() throws Exception {
        HttpResponse<String> r = post("/api/v1/invitations/" + invitation + "/extend", tokenMember(), "");
        assertNo2xx("MEMBER extend", r);
    }

    @Test
    void member_no_puede_revoke() throws Exception {
        HttpResponse<String> r = post("/api/v1/invitations/" + invitation + "/revoke", tokenMember(), "");
        assertNo2xx("MEMBER revoke", r);
    }

    @Test
    void member_no_puede_request_exit() throws Exception {
        HttpResponse<String> r = post("/api/v1/invitations/" + invitation + "/request-exit", tokenMember(), "");
        assertNo2xx("MEMBER request-exit", r);
    }

    @Test
    void member_no_puede_agregar_familiar() throws Exception {
        UUID candidate = UUID.randomUUID();
        insertUser(candidate);
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, candidate);
        String body = "{\"identifier\":\"%s\",\"canInvite\":false,\"relation\":\"Hijo/a\"}".formatted(email);
        HttpResponse<String> r = post("/api/v1/family/lots/" + lot + "/members", tokenMember(), body);
        assertNo2xx("MEMBER add family", r);
    }

    @Test
    void member_no_puede_patch_familiar() throws Exception {
        String body = "{\"canInvite\":false,\"active\":false}";
        HttpResponse<String> r = patch("/api/v1/family/lots/" + lot + "/members/" + owner, tokenMember(), body);
        assertNo2xx("MEMBER patch family", r);
    }

    // ---------------- residente sin permiso alguno ----------------

    @Test
    void orphan_no_puede_crear_invitacion() throws Exception {
        String body = """
                {"lotId":"%s","type":"SINGLE","guestName":"G","guestDocument":"30111222",
                 "companions":0,"validFrom":"%s","validUntil":"%s","maxUses":1}
                """.formatted(lot, Instant.now(), Instant.now().plusSeconds(3600));
        HttpResponse<String> r = post("/api/v1/invitations", tokenOrphan(), body);
        assertNo2xx("orphan create invitation", r);
    }

    @Test
    void orphan_no_puede_ver_invitacion() throws Exception {
        HttpResponse<String> r = get("/api/v1/invitations/" + invitation, tokenOrphan());
        assertNo2xx("orphan GET invitation", r);
    }

    @Test
    void orphan_no_puede_ver_familia() throws Exception {
        HttpResponse<String> r = get("/api/v1/family/lots/" + lot + "/members", tokenOrphan());
        assertNo2xx("orphan GET family", r);
    }

    @Test
    void orphan_no_puede_extend() throws Exception {
        HttpResponse<String> r = post("/api/v1/invitations/" + invitation + "/extend", tokenOrphan(), "");
        assertNo2xx("orphan extend", r);
    }

    @Test
    void orphan_no_puede_listar_invitaciones_filtrando_por_lote() throws Exception {
        HttpResponse<String> r = get("/api/v1/invitations?lotId=" + lot, tokenOrphan());
        assertNo2xx("orphan list ?lotId=lot", r);
    }

    // ---------------- helpers ----------------

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

    private HttpResponse<String> patch(String path, String token, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(5))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static void assertNo2xx(String label, HttpResponse<String> resp) {
        System.out.println("== " + label + " => HTTP " + resp.statusCode() + " ==");
        System.out.println(resp.body());
        int code = resp.statusCode();
        assertThat(code >= 200 && code < 300)
                .as("%s devolvio %d (esperado 4xx). Body: %s", label, code, resp.body())
                .isFalse();
    }

    private void insertUser(UUID id) {
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, 'h', 'X', 'ACTIVE')
                """, id, id + "@t.com");
    }
}
