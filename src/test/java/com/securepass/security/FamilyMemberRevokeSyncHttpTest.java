package com.securepass.security;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.domain.Role;
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
 * WBS-112: end-to-end del mecanismo de seguridad mas pesado del sistema.
 *
 * Cadena que verifica:
 *   1) Titular hace PATCH baja del familiar.
 *   2) La cascada revoca en DB las invitaciones del familiar en ese lote,
 *      INCLUYENDO su resident_pass (el QR permanente que YA esta en su
 *      telefono).
 *   3) El guardia hace GET /access/revoked?since=... y encuentra ahi los
 *      jti revocados.
 *   4) Esos jti son los que su device meteria en revoked_cache y usaria
 *      en validateOffline para denegar el pase.
 *
 * Si este test falla, el QR revocado sigue entrando hasta que caduque en
 * 2099. Es el guardian del comentario LOAD-BEARING en
 * ActiveInvitationJdbcRevoker.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FamilyMemberRevokeSyncHttpTest {

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

    UUID barrio;
    UUID lot;
    UUID owner;
    UUID member;
    UUID guard;
    UUID residentPassId;
    UUID residentPassJti;
    UUID guestInvitationId;
    UUID guestInvitationJti;
    HttpClient http;

    @BeforeEach
    void seed() {
        http = HttpClient.newHttpClient();
        barrio = UUID.randomUUID();
        lot = UUID.randomUUID();
        owner = UUID.randomUUID();
        member = UUID.randomUUID();
        guard = UUID.randomUUID();
        residentPassId = UUID.randomUUID();
        residentPassJti = UUID.randomUUID();
        guestInvitationId = UUID.randomUUID();
        guestInvitationJti = UUID.randomUUID();

        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'B', ?)",
                barrio, "b-" + UUID.randomUUID());
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'L-1')",
                lot, barrio);
        seedUser(owner, "owner");
        seedUser(member, "member");
        seedUser(guard, "guard");
        jdbc.update("""
                INSERT INTO memberships (user_id, neighborhood_id, role, active) VALUES
                    (?, ?, 'RESIDENT_OWNER', TRUE),
                    (?, ?, 'RESIDENT_MEMBER', TRUE),
                    (?, ?, 'GUARD', TRUE)
                """, owner, barrio, member, barrio, guard, barrio);
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active) VALUES
                    (?, ?, 'OWNER',  '{"can_invite": true, "relation": "OWNER"}'::jsonb, TRUE),
                    (?, ?, 'MEMBER', '{"can_invite": true, "relation": "HIJO"}'::jsonb, TRUE)
                """, owner, lot, member, lot);
        // Resident pass: el QR permanente del familiar.
        jdbc.update("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status, guest_name,
                    guest_document, valid_from, valid_until,
                    max_uses, used_count, token_kid, jti, resident_pass
                ) VALUES (?, ?, ?, ?, 'EVENT', 'ACTIVE', 'Residente', '00000000',
                          now() - interval '1 hour', '2099-12-31'::timestamptz,
                          1, 0, 'kid-pass', ?, TRUE)
                """, residentPassId, barrio, lot, member, residentPassJti);
        // Invitacion regular a un invitado, creada por el familiar.
        jdbc.update("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status, guest_name,
                    guest_document, valid_from, valid_until,
                    max_uses, used_count, token_kid, jti, resident_pass
                ) VALUES (?, ?, ?, ?, 'SINGLE', 'ACTIVE', 'Invitado', '30111222',
                          now() - interval '1 hour', now() + interval '1 hour',
                          1, 0, 'kid-guest', ?, FALSE)
                """, guestInvitationId, barrio, lot, member, guestInvitationJti);
    }

    private void seedUser(UUID id, String label) {
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, 'h', ?, 'ACTIVE')
                """, id, label + "-" + id + "@t.com", label);
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

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void bajaDeFamiliar_publicaJtiDelResidentPassEnAccessRevoked() throws Exception {
        String ownerToken = jwt.issue(owner, barrio, List.of(Role.RESIDENT_OWNER), List.of(lot)).token();
        String guardToken = jwt.issue(guard, barrio, List.of(Role.GUARD), List.of()).token();

        // 't0 - 1s' para tolerar que revoked_at quede en el mismo segundo del cutoff.
        Instant since = Instant.now().minusSeconds(1);

        // Paso 1: titular da de baja al familiar.
        HttpResponse<String> patchResp = patch(
                "/api/v1/family/lots/" + lot + "/members/" + member,
                ownerToken,
                "{\"active\":false}");
        System.out.println("== PATCH baja => HTTP " + patchResp.statusCode() + " ==");
        System.out.println(patchResp.body());
        assertThat(patchResp.statusCode()).isEqualTo(200);
        assertThat(patchResp.body()).contains("\"outcome\":\"REVOKED_MEMBER\"");
        assertThat(patchResp.body()).contains("\"revokedInvitationsCount\":2");

        // Paso 2: garita sincroniza revoked.
        HttpResponse<String> revokedResp = get(
                "/api/v1/access/revoked?since=" + since.toString(), guardToken);
        System.out.println("== GET /access/revoked => HTTP " + revokedResp.statusCode() + " ==");
        System.out.println(revokedResp.body());
        assertThat(revokedResp.statusCode()).isEqualTo(200);
        // Los dos jti del familiar tienen que estar. Los del owner (que no fue
        // afectado) no aparecen porque no hay ninguna invitacion revocada.
        assertThat(revokedResp.body()).contains(residentPassJti.toString());
        assertThat(revokedResp.body()).contains(guestInvitationJti.toString());
    }
}
