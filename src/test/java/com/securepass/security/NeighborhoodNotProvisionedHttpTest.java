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
 * Barrio sin signing key: POST /invitations debe responder 503 con
 * code NEIGHBORHOOD_NOT_PROVISIONED, no 500 ciego. Regresion del smoke
 * que golpeo con el seed dev (issue #128).
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
    @Autowired JwtService jwt;

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
        jdbc.update("""
                INSERT INTO family_members (lot_id, user_id, relation, is_owner, can_invite, active)
                VALUES (?, ?, 'OWNER', TRUE, TRUE, TRUE)
                """, lot, owner);
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
}
