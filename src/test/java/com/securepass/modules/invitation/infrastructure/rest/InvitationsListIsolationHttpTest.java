package com.securepass.modules.invitation.infrastructure.rest;

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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /invitations sin lotes de residente. El repositorio lee una lista de
 * lotes vacia como "sin filtro" (la del admin): un guardia, un trabajador o un
 * familiar dado de baja listaba todas las invitaciones del barrio.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InvitationsListIsolationHttpTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;

    final HttpClient http = HttpClient.newHttpClient();
    UUID barrio;
    UUID lot;
    UUID owner;

    @BeforeEach
    void seed() {
        barrio = UUID.randomUUID();
        lot = UUID.randomUUID();
        owner = UUID.randomUUID();
        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'B', ?)", barrio, "b-" + barrio);
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'L-1')", lot, barrio);
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, 'h', 'Titular', 'ACTIVE')",
                owner, "o-" + owner + "@t.com");
        jdbc.update("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status,
                    guest_name, guest_document, companions,
                    valid_from, valid_until, max_uses, used_count, token_kid, jti
                ) VALUES (?, ?, ?, ?, 'SINGLE', 'ACTIVE', 'Invitada Ajena', '30111222', 0, ?, ?, 1, 0, 'kid-1', ?)
                """, UUID.randomUUID(), barrio, lot, owner,
                Timestamp.from(Instant.now().minusSeconds(60)), Timestamp.from(Instant.now().plusSeconds(3600)),
                UUID.randomUUID());
    }

    private HttpResponse<String> list(List<Role> roles, List<UUID> lots) throws Exception {
        String token = jwt.issue(UUID.randomUUID(), barrio, roles, lots).token();
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/invitations"))
                .header("Authorization", "Bearer " + token)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void residenteSinLotesNoVeLasInvitacionesDelBarrio() throws Exception {
        HttpResponse<String> resp = list(List.of(Role.RESIDENT_MEMBER), List.of());

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("\"total\":0").doesNotContain("Invitada Ajena");
    }

    @Test
    void guardiaNoListaInvitaciones() throws Exception {
        assertThat(list(List.of(Role.GUARD), List.of()).statusCode()).isEqualTo(403);
    }

    @Test
    void residenteDelLoteSigueViendoLaSuya() throws Exception {
        HttpResponse<String> resp = list(List.of(Role.RESIDENT_OWNER), List.of(lot));

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("Invitada Ajena").contains("\"total\":1");
    }
}
