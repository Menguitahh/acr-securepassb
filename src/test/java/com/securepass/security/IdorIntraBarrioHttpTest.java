package com.securepass.security;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Baseline IDOR intra-barrio cross-lote a nivel HTTP (pre WBS-108).
 *
 * A diferencia de IdorIntraBarrioTest (que llama use cases directamente),
 * este suite hace requests HTTP reales con JWT emitido por JwtTokenProvider,
 * de modo que el @PreAuthorize se ejecuta antes que la logica de use case.
 *
 * Un STATUS 200/201 aca es un IDOR intra-barrio real: el residente del
 * lote A pudo tocar recursos del lote B via HTTP publico.
 *
 * ALCANCE DE ESTE BASELINE
 * ------------------------
 * Congela el comportamiento OBSERVABLE por HTTP hoy: cada request rebota,
 * no revela recursos ajenos. NO congela la implementacion (que la defensa
 * viva en el use case, ni el status code exacto).
 *
 * Cuando WBS-108 mueva la defensa al gate (@PreAuthorize contra permisos
 * por lote), algunos status codes pueden cambiar legitimamente:
 *   - Un gate rechaza antes de que el use case sepa si el recurso existe,
 *     asi que un 404 de use case puede pasar a 403 de gate, o viceversa
 *     si el gate se disena para preservar anti-enumeracion.
 *   - El mensaje de detail puede cambiar (de "usuario no pertenece al
 *     lote" a "Sin permisos suficientes" o similar).
 *
 * Un cambio de status en PR2 NO es automaticamente una regresion, pero
 * SI requiere decision explicita del ticket: cada test que falle en PR2
 * hay que verlo, decidir si el nuevo status es aceptable, y actualizar
 * la assertion (o revertir el cambio) con la razon en el commit. No
 * relajar el rango de status esperados por conveniencia.
 *
 * Lo que NO puede cambiar sin ser un bug real: que devuelvan 2xx.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdorIntraBarrioHttpTest {

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
    @Autowired ObjectMapper json;
    @Autowired SigningKeyIssuer signingKeyIssuer;

    UUID barrio;
    UUID gate;
    UUID lotA;
    UUID lotB;
    UUID residentA;
    UUID residentB;
    UUID memberOfLotB;
    UUID invitationB;
    HttpClient http;

    @BeforeEach
    void seed() {
        http = HttpClient.newHttpClient();

        barrio = UUID.randomUUID();
        gate = UUID.randomUUID();
        lotA = UUID.randomUUID();
        lotB = UUID.randomUUID();
        residentA = UUID.randomUUID();
        residentB = UUID.randomUUID();
        memberOfLotB = UUID.randomUUID();

        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'X', ?)",
                barrio, "n-" + UUID.randomUUID());
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, 'g', 'BOTH')",
                gate, barrio);
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'A-1')", lotA, barrio);
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'B-1')", lotB, barrio);
        signingKeyIssuer.issueForNeighborhood(barrio);

        insertUser(residentA);
        insertUser(residentB);
        insertUser(memberOfLotB);

        // residentA: owner de lotA
        insertLotOwnership(residentA, lotA);
        // residentB: owner de lotB
        insertLotOwnership(residentB, lotB);
        // memberOfLotB: miembro (no owner) de lotB, para PATCH
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'MEMBER', jsonb_build_object('can_invite', TRUE, 'relation', 'Hijo/a'), TRUE)
                """, memberOfLotB, lotB);

        // Invitation activa en lotB creada por residentB
        invitationB = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status,
                    guest_name, guest_document, companions, valid_from, valid_until,
                    max_uses, used_count, token_kid, jti
                ) VALUES (?, ?, ?, ?, 'SINGLE', 'ACTIVE', 'Maria', '30123456', 0, ?, ?, 1, 0, 'kid-b', ?)
                """, invitationB, barrio, lotB, residentB,
                Timestamp.from(now.minusSeconds(60)),
                Timestamp.from(now.plusSeconds(3600)), UUID.randomUUID());
    }

    private String tokenResidentA() {
        return jwt.issue(residentA, barrio, List.of(Role.RESIDENT_OWNER), List.of(lotA)).token();
    }

    // ---------- 1. crear invitacion con lotId=lotB ----------
    @Test
    void POST_invitations_conLotB() throws Exception {
        String body = """
                {"lotId":"%s","type":"SINGLE","guestName":"Guest","guestDocument":"30111222",
                 "companions":0,"validFrom":"%s","validUntil":"%s","maxUses":1}
                """.formatted(lotB, Instant.now(), Instant.now().plusSeconds(3600));
        HttpResponse<String> resp = post("/api/v1/invitations", tokenResidentA(), body);
        report("POST /invitations lotB", resp);
    }

    // ---------- 2. revocar invitacion del lotB ----------
    @Test
    void POST_invitations_id_revoke_lotB() throws Exception {
        HttpResponse<String> resp = post("/api/v1/invitations/" + invitationB + "/revoke",
                tokenResidentA(), "");
        report("POST /invitations/{id}/revoke lotB", resp);
    }

    // ---------- 3. extender invitacion del lotB ----------
    @Test
    void POST_invitations_id_extend_lotB() throws Exception {
        HttpResponse<String> resp = post("/api/v1/invitations/" + invitationB + "/extend",
                tokenResidentA(), "");
        report("POST /invitations/{id}/extend lotB", resp);
    }

    // ---------- 4. GET invitacion del lotB por id ----------
    @Test
    void GET_invitations_id_lotB() throws Exception {
        HttpResponse<String> resp = get("/api/v1/invitations/" + invitationB, tokenResidentA());
        report("GET /invitations/{id} lotB", resp);
    }

    // ---------- 5. GET familia del lotB ----------
    @Test
    void GET_family_lots_lotB_members() throws Exception {
        HttpResponse<String> resp = get("/api/v1/family/lots/" + lotB + "/members", tokenResidentA());
        report("GET /family/lots/{lotB}/members", resp);
    }

    // ---------- 6. POST agregar miembro al lotB ----------
    @Test
    void POST_family_lots_lotB_members() throws Exception {
        // WBS-110: body por DNI + activacion. Aca no importa que la carga sea
        // real, importa que el gate rechace antes de tocar la DB.
        String body = """
                {"fullName":"Nuevo","dni":"39999999","birthDate":"1990-01-01",
                 "email":"nuevo@dev.local","relation":"Hijo/a","canInvite":false}
                """;
        HttpResponse<String> resp = post("/api/v1/family/lots/" + lotB + "/members", tokenResidentA(), body);
        report("POST /family/lots/{lotB}/members", resp);
    }

    // ---------- 7. PATCH miembro del lotB ----------
    @Test
    void PATCH_family_lots_lotB_members_userId() throws Exception {
        String body = "{\"canInvite\":false,\"active\":false}";
        HttpResponse<String> resp = patch(
                "/api/v1/family/lots/" + lotB + "/members/" + memberOfLotB,
                tokenResidentA(), body);
        report("PATCH /family/lots/{lotB}/members/{userId}", resp);
    }

    // ---------- 8. GET access-logs filtrando por lotB ----------
    @Test
    void GET_access_logs_filtroLotB() throws Exception {
        HttpResponse<String> resp = get("/api/v1/access-logs?lotId=" + lotB, tokenResidentA());
        report("GET /access-logs?lotId=lotB", resp);
    }

    // ---------- #123: 404 uniforme en endpoints por id de invitacion ----------
    @Test
    void invitacionAjenaEInexistenteDanElMismo404() throws Exception {
        UUID inexistente = UUID.randomUUID();
        for (UUID id : List.of(invitationB, inexistente)) {
            org.assertj.core.api.Assertions.assertThat(
                    get("/api/v1/invitations/" + id, tokenResidentA()).statusCode()).isEqualTo(404);
            for (String action : List.of("revoke", "extend", "request-exit")) {
                org.assertj.core.api.Assertions.assertThat(
                        post("/api/v1/invitations/" + id + "/" + action, tokenResidentA(), "").statusCode())
                        .as(action + " " + id).isEqualTo(404);
            }
        }
    }

    @Test
    void miembroDelLoteQueVeLaInvitacionRecibe403AlRevocar() throws Exception {
        String member = jwt.issue(memberOfLotB, barrio, List.of(Role.RESIDENT_MEMBER), List.of(lotB)).token();
        org.assertj.core.api.Assertions.assertThat(
                post("/api/v1/invitations/" + invitationB + "/revoke", member, "").statusCode()).isEqualTo(403);
    }

    @Test
    void adminDeOtroBarrioRecibe404() throws Exception {
        UUID otro = UUID.randomUUID();
        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'Y', ?)", otro, "n-" + otro);
        String admin = jwt.issue(UUID.randomUUID(), otro, List.of(Role.ADMIN), List.of()).token();
        org.assertj.core.api.Assertions.assertThat(
                get("/api/v1/invitations/" + invitationB, admin).statusCode()).isEqualTo(404);
        org.assertj.core.api.Assertions.assertThat(
                post("/api/v1/invitations/" + invitationB + "/revoke", admin, "").statusCode()).isEqualTo(404);
    }

    // ---------- helpers ----------

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

    private void report(String label, HttpResponse<String> resp) {
        System.out.println("== " + label + " => HTTP " + resp.statusCode() + " ==");
        System.out.println(resp.body());
        System.out.println();
        // Assertion: cualquier 2xx es un IDOR real.
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            throw new AssertionError(label + " devolvio " + resp.statusCode()
                    + " (IDOR intra-barrio). Body: " + resp.body());
        }
    }

    private void insertUser(UUID id) {
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, 'h', 'X', 'ACTIVE')
                """, id, id + "@t.com");
    }

    private void insertLotOwnership(UUID userId, UUID lotId) {
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'OWNER', jsonb_build_object('can_invite', TRUE, 'relation', 'OWNER'), TRUE)
                """, userId, lotId);
        jdbc.update("""
                INSERT INTO memberships (user_id, neighborhood_id, role, active)
                VALUES (?, ?, 'RESIDENT_OWNER', TRUE)
                ON CONFLICT (user_id, neighborhood_id, role) DO UPDATE SET active = TRUE
                """, userId, barrio);
    }
}
