package com.securepass.modules.access.infrastructure.rest;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.invitation.application.QrTokenIssuer;
import com.securepass.modules.invitation.application.SigningKeyIssuer;
import com.securepass.modules.invitation.domain.InvitationType;
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
 * La card de permanencias por vencer del residente (WBS-72) consume
 * GET /stays/open. Era solo GUARD/ADMIN: el residente recibia 403 siempre y
 * la card nunca aparecia. Ahora el residente ve las estadias abiertas de sus
 * lotes y nada mas; guardia y admin siguen viendo todo el barrio.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResidentOpenStaysHttpTest {

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
    @Autowired SigningKeyIssuer signingKeys;
    @Autowired QrTokenIssuer qrTokenIssuer;

    private final HttpClient http = HttpClient.newHttpClient();
    private UUID nbh;
    private UUID owner;
    private UUID guard;
    private UUID lotA;
    private UUID lotB;
    private UUID gate;

    @BeforeEach
    void setUp() throws Exception {
        nbh = UUID.randomUUID();
        owner = UUID.randomUUID();
        guard = UUID.randomUUID();
        lotA = UUID.randomUUID();
        lotB = UUID.randomUUID();
        gate = UUID.randomUUID();
        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'ResidentStays', ?)",
                nbh, "resident-stays-" + nbh);
        signingKeys.issueForNeighborhood(nbh);
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, 'h', 'Titular', 'ACTIVE')",
                owner, "o-" + owner + "@t.com");
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, 'h', 'Guardia', 'ACTIVE')",
                guard, "g-" + guard + "@t.com");
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'A-1')", lotA, nbh);
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'B-1')", lotB, nbh);
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, 'Principal', 'BOTH')",
                gate, nbh);
        enterGuest(lotA, "Invitado A", "30100100");
        enterGuest(lotB, "Invitado B", "30200200");
    }

    @Test
    void residenteVeSoloLasEstadiasDeSusLotes() throws Exception {
        String residentJwt = jwt.issue(owner, nbh, List.of(Role.RESIDENT_OWNER), List.of(lotA)).token();
        HttpResponse<String> res = get(residentJwt);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("Invitado A").doesNotContain("Invitado B");
    }

    @Test
    void residenteSinLotesNoVeNada() throws Exception {
        String residentJwt = jwt.issue(owner, nbh, List.of(Role.RESIDENT_MEMBER), List.of()).token();
        HttpResponse<String> res = get(residentJwt);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).doesNotContain("Invitado A").doesNotContain("Invitado B");
    }

    @Test
    void guardiaSigueViendoTodoElBarrio() throws Exception {
        String guardJwt = jwt.issue(guard, nbh, List.of(Role.GUARD), List.of()).token();
        HttpResponse<String> res = get(guardJwt);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("Invitado A").contains("Invitado B");
    }

    // Ingreso real (enroll con firma Ed25519) para que la estadia tenga su log.
    private void enterGuest(UUID lot, String name, String dni) throws Exception {
        Instant now = Instant.now();
        Instant from = now.minusSeconds(60);
        Instant until = now.plusSeconds(3600);
        UUID invitation = UUID.randomUUID();
        UUID jti = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status,
                    guest_name, guest_document, companions, valid_from, valid_until,
                    max_uses, used_count, token_kid, jti
                ) VALUES (?, ?, ?, ?, 'SINGLE', 'ACTIVE', ?, ?, 0, ?, ?, 1, 0,
                          (SELECT kid FROM signing_keys WHERE neighborhood_id=? AND active=TRUE), ?)
                """, invitation, nbh, lot, owner, name, dni, Timestamp.from(from), Timestamp.from(until), nbh, jti);
        String token = qrTokenIssuer.issue(new QrTokenIssuer.IssueCommand(
                nbh, lot, invitation, jti, InvitationType.SINGLE, from, until, 1));
        String guardJwt = jwt.issue(guard, nbh, List.of(Role.GUARD), List.of()).token();
        String body = "{\"gateId\":\"" + gate + "\",\"token\":\"" + token + "\",\"documentNumber\":\"" + dni
                + "\",\"capturedAt\":\"" + now + "\",\"clientEventId\":\"" + UUID.randomUUID() + "\"}";
        HttpResponse<String> res = http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/access/enroll"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + guardJwt)
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.body()).contains("\"result\":\"ALLOWED\"");
    }

    private HttpResponse<String> get(String token) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/stays/open"))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(5))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
