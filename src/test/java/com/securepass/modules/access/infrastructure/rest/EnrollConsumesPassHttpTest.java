package com.securepass.modules.access.infrastructure.rest;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.invitation.application.QrTokenIssuer;
import com.securepass.modules.invitation.application.SigningKeyIssuer;
import com.securepass.modules.invitation.domain.InvitationType;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El enrolamiento es el primer ingreso del invitado: tiene que consumir el
 * pase igual que validate. Antes el ALLOWED del enroll guardaba el log y
 * abria la estadia sin tocar used_count: el pase de un solo uso seguia
 * ACTIVE y el mismo QR volvia a entrar (spec 14, criterio 4). Tampoco
 * miraba la vigencia: un pase vencido se enrolaba y entraba.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EnrollConsumesPassHttpTest {

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
    @Autowired QrTokenIssuer qrTokenIssuer;

    private final HttpClient http = HttpClient.newHttpClient();
    private UUID nbh;
    private UUID owner;
    private UUID guard;
    private UUID lot;
    private UUID gate;
    private String guardJwt;

    @BeforeEach
    void setUp() {
        nbh = UUID.randomUUID();
        owner = UUID.randomUUID();
        guard = UUID.randomUUID();
        lot = UUID.randomUUID();
        gate = UUID.randomUUID();
        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'EnrollConsume', ?)",
                nbh, "enroll-consume-" + nbh);
        signingKeys.issueForNeighborhood(nbh);
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, 'h', 'Titular', 'ACTIVE')",
                owner, "o-" + owner + "@t.com");
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, 'h', 'Guardia', 'ACTIVE')",
                guard, "g-" + guard + "@t.com");
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'B-1')", lot, nbh);
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, 'Principal', 'BOTH')",
                gate, nbh);
        guardJwt = jwt.issue(guard, nbh, List.of(Role.GUARD), List.of()).token();
    }

    @Test
    void enrollAllowedConsumeElPaseYElMismoQrNoVuelveAEntrar() throws Exception {
        Instant now = Instant.now();
        UUID invitation = UUID.randomUUID();
        String token = invitation(invitation, "30111000", now.minusSeconds(60), now.plusSeconds(3600));

        HttpResponse<String> enroll = enroll(token, "30111000");
        assertThat(enroll.statusCode()).isEqualTo(200);
        assertThat(enroll.body()).contains("\"result\":\"ALLOWED\"");

        assertThat(jdbc.queryForObject("SELECT used_count FROM invitations WHERE id=?", Integer.class, invitation))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM invitations WHERE id=?", String.class, invitation))
                .isEqualTo("USED");

        HttpResponse<String> again = validate(token);
        assertThat(again.body()).contains("\"result\":\"DENIED\"").contains("\"reason\":\"ALREADY_USED\"");
    }

    @Test
    void enrollDePaseVencidoSeRechazaSinConsumir() throws Exception {
        Instant now = Instant.now();
        UUID invitation = UUID.randomUUID();
        String token = invitation(invitation, "30222000", now.minusSeconds(7200), now.minusSeconds(3600));

        HttpResponse<String> enroll = enroll(token, "30222000");
        assertThat(enroll.statusCode()).isEqualTo(200);
        assertThat(enroll.body()).contains("\"result\":\"DENIED\"").contains("\"reason\":\"EXPIRED\"");
        assertThat(jdbc.queryForObject("SELECT used_count FROM invitations WHERE id=?", Integer.class, invitation))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM stays WHERE invitation_id=?", Integer.class, invitation)).isZero();
    }

    private String invitation(UUID id, String dni, Instant from, Instant until) {
        UUID jti = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status,
                    guest_name, guest_document, companions, valid_from, valid_until,
                    max_uses, used_count, token_kid, jti
                ) VALUES (?, ?, ?, ?, 'SINGLE', 'ACTIVE', 'Invitado', ?, 0, ?, ?, 1, 0,
                          (SELECT kid FROM signing_keys WHERE neighborhood_id=? AND active=TRUE), ?)
                """, id, nbh, lot, owner, dni, Timestamp.from(from), Timestamp.from(until), nbh, jti);
        return qrTokenIssuer.issue(new QrTokenIssuer.IssueCommand(
                nbh, lot, id, jti, InvitationType.SINGLE, from, until, 1));
    }

    private HttpResponse<String> enroll(String token, String dni) throws Exception {
        String body = "{\"gateId\":\"" + gate + "\",\"token\":\"" + token + "\",\"documentNumber\":\"" + dni
                + "\",\"capturedAt\":\"" + Instant.now() + "\",\"clientEventId\":\"" + UUID.randomUUID() + "\"}";
        return post("/api/v1/access/enroll", body);
    }

    private HttpResponse<String> validate(String token) throws Exception {
        String body = "{\"gateId\":\"" + gate + "\",\"token\":\"" + token + "\",\"direction\":\"IN\","
                + "\"capturedAt\":\"" + Instant.now() + "\",\"clientEventId\":\"" + UUID.randomUUID() + "\"}";
        return post("/api/v1/access/validate", body);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + guardJwt)
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
