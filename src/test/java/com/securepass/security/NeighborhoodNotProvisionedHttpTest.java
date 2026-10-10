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
 * Barrio sin signing key: los endpoints que necesitan firmar QR deben
 * responder 503 con code NEIGHBORHOOD_NOT_PROVISIONED, no 500 ciego.
 * Regresion del smoke que golpeo con el seed dev (issue #128).
 *
 * Cubre POST /invitations, GET /me/passes y GET /invitations/{id}, los
 * tres caminos que golpean la signing key en el mismo request.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NeighborhoodNotProvisionedHttpTest {

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
    @Autowired SigningKeyIssuer signingKeys;

    UUID barrio;
    UUID lot;
    UUID owner;
    HttpClient http;

    @BeforeEach
    void seed() {
        http = HttpClient.newHttpClient();
        barrio = UUID.randomUUID();
        lot = UUID.randomUUID();
        owner = UUID.randomUUID();

        // Notablemente: NO llamo a signingKeyIssuer.issueForNeighborhood(barrio).
        // Espejo de un barrio recien creado sin aprovisionar.
        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'B', ?)",
                barrio, "b-" + UUID.randomUUID());
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'L-1')",
                lot, barrio);
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, 'h', 'owner', 'ACTIVE')
                """, owner, "o-" + owner + "@t.com");
        jdbc.update("""
                INSERT INTO memberships (user_id, neighborhood_id, role, active)
                VALUES (?, ?, 'RESIDENT_OWNER', TRUE)
                """, owner, barrio);
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'OWNER', jsonb_build_object('can_invite', TRUE, 'relation', 'OWNER'), TRUE)
                """, owner, lot);
    }

    @Test
    void postInvitations_sinSigningKey_devuelve503_con_code() throws Exception {
        String token = jwt.issue(owner, barrio, List.of(Role.RESIDENT_OWNER), List.of(lot)).token();
        String body = """
                {"lotId":"%s","type":"SINGLE","guestName":"G","guestDocument":"30111222",
                 "companions":0,"validFrom":"%s","validUntil":"%s","maxUses":1}
                """.formatted(lot, Instant.now(), Instant.now().plusSeconds(3600));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/invitations"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        System.out.println("== POST /invitations sin signing key => HTTP " + resp.statusCode() + " ==");
        System.out.println(resp.body());

        assertThat(resp.statusCode()).isEqualTo(503);
        assertThat(resp.body()).contains("\"code\":\"NEIGHBORHOOD_NOT_PROVISIONED\"");
        assertThat(resp.body()).contains("\"neighborhoodId\":\"" + barrio + "\"");
        assertThat(resp.body()).contains("\"missing\":\"signing key activa\"");
        assertThat(resp.body()).contains("neighborhood-not-provisioned");
        assertThat(resp.body()).doesNotContain("internal-error");
    }

    @Test
    void getMePasses_sinSigningKey_devuelve503_con_code() throws Exception {
        String token = jwt.issue(owner, barrio, List.of(Role.RESIDENT_OWNER), List.of(lot)).token();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/me/passes"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        System.out.println("== GET /me/passes sin signing key => HTTP " + resp.statusCode() + " ==");
        System.out.println(resp.body());

        assertThat(resp.statusCode()).isEqualTo(503);
        assertThat(resp.body()).contains("\"code\":\"NEIGHBORHOOD_NOT_PROVISIONED\"");
        assertThat(resp.body()).contains("\"neighborhoodId\":\"" + barrio + "\"");
        assertThat(resp.body()).contains("\"missing\":\"signing key activa\"");
        assertThat(resp.body()).doesNotContain("internal-error");
    }

    @Test
    void getInvitationById_sinSigningKey_devuelve503_con_code() throws Exception {
        // La invitacion ya existe (fue creada antes de que borraran la signing key
        // o durante una ventana en la que existia). GET /{id} vuelve a firmar el QR
        // en cada request, por eso golpea el mismo camino.
        UUID invId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status,
                    guest_name, guest_document, companions,
                    valid_from, valid_until,
                    max_uses, used_count, token_kid, jti, resident_pass, created_at)
                VALUES (?, ?, ?, ?, 'SINGLE', 'ACTIVE',
                        'Invitado', '30111222', 0,
                        ?, ?,
                        1, 0, 'kid-orphan', ?, FALSE, ?)
                """, invId, barrio, lot, owner,
                java.sql.Timestamp.from(now.minusSeconds(60)),
                java.sql.Timestamp.from(now.plusSeconds(3600)),
                UUID.randomUUID(),
                java.sql.Timestamp.from(now));

        String token = jwt.issue(owner, barrio, List.of(Role.RESIDENT_OWNER), List.of(lot)).token();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/invitations/" + invId))
                .header("Authorization", "Bearer " + token)
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        System.out.println("== GET /invitations/{id} sin signing key => HTTP " + resp.statusCode() + " ==");
        System.out.println(resp.body());

        assertThat(resp.statusCode()).isEqualTo(503);
        assertThat(resp.body()).contains("\"code\":\"NEIGHBORHOOD_NOT_PROVISIONED\"");
        assertThat(resp.body()).contains("\"neighborhoodId\":\"" + barrio + "\"");
        assertThat(resp.body()).contains("\"missing\":\"signing key activa\"");
        assertThat(resp.body()).doesNotContain("internal-error");
    }

    // #157 E: la clave existe pero no se puede descifrar (master key rotada,
    // fila corrupta). Antes era 500 internal-error.
    @Test
    void postInvitations_conSigningKeyIlegible_devuelve503_con_code() throws Exception {
        signingKeys.issueForNeighborhood(barrio);
        jdbc.update("UPDATE signing_keys SET private_key_enc = 'bm8tZXMtdW4tY2lmcmFkby12YWxpZG8tcGFyYS1lc3RhLWNsYXZl' WHERE neighborhood_id = ?",
                barrio);
        String token = jwt.issue(owner, barrio, List.of(Role.RESIDENT_OWNER), List.of(lot)).token();
        String body = """
                {"lotId":"%s","type":"SINGLE","guestName":"G","guestDocument":"30111222",
                 "companions":0,"validFrom":"%s","validUntil":"%s","maxUses":1}
                """.formatted(lot, Instant.now(), Instant.now().plusSeconds(3600));

        HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/invitations"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(5))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(503);
        assertThat(resp.body()).contains("\"code\":\"SIGNING_KEY_UNREADABLE\"").doesNotContain("internal-error");
    }
}
