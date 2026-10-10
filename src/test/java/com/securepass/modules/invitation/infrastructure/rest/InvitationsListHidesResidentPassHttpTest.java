package com.securepass.modules.invitation.infrastructure.rest;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.invitation.application.SigningKeyIssuer;
import com.securepass.shared.security.JwtTokenProvider;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El QR personal del residente (GET /me/passes, US-16) se guarda como una
 * invitacion EVENT con resident_pass=TRUE. GET /invitations lo listaba junto
 * a los pases de los invitados: en la home aparecia "Residente · Evento ·
 * Usos restantes: 1". Tiene su propia pantalla; la lista no lo muestra.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InvitationsListHidesResidentPassHttpTest {

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
    @Autowired SigningKeyIssuer signingKeys;

    @Test
    void laListaDeInvitacionesNoIncluyeElQrPersonal() throws Exception {
        UUID barrio = UUID.randomUUID();
        UUID lot = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'B', ?)", barrio, "b-" + barrio);
        signingKeys.issueForNeighborhood(barrio);
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'L-1')", lot, barrio);
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, 'h', 'owner', 'ACTIVE')",
                owner, "o-" + owner + "@t.com");
        jdbc.update("INSERT INTO memberships (user_id, neighborhood_id, role, active) VALUES (?, ?, 'RESIDENT_OWNER', TRUE)",
                owner, barrio);
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'OWNER', jsonb_build_object('can_invite', TRUE, 'relation', 'OWNER'), TRUE)
                """, owner, lot);
        String token = jwt.issue(owner, barrio, List.of(Role.RESIDENT_OWNER), List.of(lot)).token();
        HttpClient http = HttpClient.newHttpClient();

        HttpResponse<String> passes = http.send(get("/api/v1/me/passes", token), HttpResponse.BodyHandlers.ofString());
        assertThat(passes.statusCode()).isEqualTo(200);
        String body = """
                {"lotId":"%s","type":"SINGLE","guestName":"Invitada","guestDocument":"30111222",
                 "companions":0,"validFrom":"%s","validUntil":"%s","maxUses":1}
                """.formatted(lot, Instant.now(), Instant.now().plusSeconds(3600));
        HttpResponse<String> created = http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/invitations"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isBetween(200, 201);

        HttpResponse<String> list = http.send(get("/api/v1/invitations?status=ACTIVE", token),
                HttpResponse.BodyHandlers.ofString());
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("Invitada").doesNotContain("\"guestName\":\"Residente\"");
        assertThat(list.body()).contains("\"total\":1");
    }

    private HttpRequest get(String path, String token) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .GET().build();
    }
}
