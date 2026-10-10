package com.securepass.security;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.domain.Role;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WBS-110: contrato v2 de POST /api/v1/family/lots/{lotId}/members.
 *
 * Golpea el path completo (filtro + @PreAuthorize + serializacion del
 * ProblemDetail) para las respuestas que la UI tiene que interpretar:
 *   - 400 AGE_UNDER_16 con redirectTo (menor de 16).
 *   - 400 EMAIL_REQUIRED (mayor sin email).
 *   - 400 validation-error (canInvite ausente).
 *   - 403 forbidden (gate del titular sobre lote ajeno).
 * El happy path del alta con activacion se valida en el smoke por curl
 * (necesita el mail listener corriendo y un barrio real).
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AddFamilyMemberHttpTest {

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

    UUID barrio;
    UUID lot;
    UUID owner;
    UUID otherOwner;
    UUID otherLot;
    HttpClient http;

    @BeforeEach
    void seed() {
        http = HttpClient.newHttpClient();
        barrio = UUID.randomUUID();
        lot = UUID.randomUUID();
        otherLot = UUID.randomUUID();
        owner = UUID.randomUUID();
        otherOwner = UUID.randomUUID();

        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'B', ?)",
                barrio, "b-" + UUID.randomUUID());
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'L-1')",
                lot, barrio);
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'L-2')",
                otherLot, barrio);
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, 'h', 'owner', 'ACTIVE')
                """, owner, "o-" + owner + "@t.com");
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, 'h', 'other', 'ACTIVE')
                """, otherOwner, "o-" + otherOwner + "@t.com");
        jdbc.update("""
                INSERT INTO memberships (user_id, neighborhood_id, role, active)
                VALUES (?, ?, 'RESIDENT_OWNER', TRUE)
                """, owner, barrio);
        jdbc.update("""
                INSERT INTO memberships (user_id, neighborhood_id, role, active)
                VALUES (?, ?, 'RESIDENT_OWNER', TRUE)
                """, otherOwner, barrio);
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'OWNER', jsonb_build_object('can_invite', TRUE, 'relation', 'OWNER'), TRUE)
                """, owner, lot);
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'OWNER', jsonb_build_object('can_invite', TRUE, 'relation', 'OWNER'), TRUE)
                """, otherOwner, otherLot);
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

    @Test
    void menorDeDieciseisDevuelve400AgeUnderSixteenConRedirect() throws Exception {
        String bd = LocalDate.now().minusYears(15).minusDays(1).toString();
        String body = """
                {"fullName":"Kid","dni":"55555555","birthDate":"%s",
                 "email":"kid@dev.local","relation":"Hijo","canInvite":true}
                """.formatted(bd);

        HttpResponse<String> resp = post("/api/v1/family/lots/" + lot + "/members", tokenOwner(), body);

        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("\"code\":\"AGE_UNDER_16\"");
        assertThat(resp.body()).contains("\"minAge\":16");
        assertThat(resp.body()).contains("\"redirectTo\":\"/api/v1/family/lots/" + lot + "/minors\"");
        assertThat(resp.body()).doesNotContain("internal-error");
    }

    @Test
    void mayorSinEmailDevuelve400EmailRequired() throws Exception {
        String bd = LocalDate.now().minusYears(30).toString();
        String body = """
                {"fullName":"Maria","dni":"55555556","birthDate":"%s",
                 "relation":"Hija","canInvite":true}
                """.formatted(bd);

        HttpResponse<String> resp = post("/api/v1/family/lots/" + lot + "/members", tokenOwner(), body);

        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("\"code\":\"EMAIL_REQUIRED\"");
        assertThat(resp.body()).doesNotContain("internal-error");
    }

    @Test
    void canInviteAusenteDevuelve400Validation() throws Exception {
        String bd = LocalDate.now().minusYears(30).toString();
        String body = """
                {"fullName":"Maria","dni":"55555557","birthDate":"%s",
                 "email":"maria@dev.local","relation":"Hija"}
                """.formatted(bd);

        HttpResponse<String> resp = post("/api/v1/family/lots/" + lot + "/members", tokenOwner(), body);

        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("validation-error");
        assertThat(resp.body()).contains("canInvite");
    }

    // Regresion: user SUSPENDED con password_hash NULL en DB (estado
    // legitimo de un soft-revoke) hacia reventar el invariante de User al
    // ser mapeado por findByDocument, y el error se filtraba como 500
    // "Error interno". El endpoint tiene que traducir a 409 USER_SUSPENDED.
    @Test
    void dniDeSuspendedSinHashDevuelve409UserSuspended() throws Exception {
        UUID suspendedId = UUID.randomUUID();
        String dni = "55555591";
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, document_number, status)
                VALUES (?, ?, NULL, 'Sus User', ?, 'SUSPENDED')
                """, suspendedId, "sus-" + suspendedId + "@t.com", dni);

        String body = """
                {"fullName":"Nuevo","dni":"%s","birthDate":"%s",
                 "email":"nuevo@dev.local","canInvite":false}
                """.formatted(dni, LocalDate.now().minusYears(30));

        HttpResponse<String> resp = post("/api/v1/family/lots/" + lot + "/members", tokenOwner(), body);

        assertThat(resp.statusCode()).isEqualTo(409);
        assertThat(resp.body()).contains("\"code\":\"USER_SUSPENDED\"");
        assertThat(resp.body()).contains("\"existingUserId\":\"" + suspendedId + "\"");
        assertThat(resp.body()).doesNotContain("internal-error");
    }

    @Test
    void dniDeDeletedSinHashDevuelve409UserDeleted() throws Exception {
        UUID deletedId = UUID.randomUUID();
        String dni = "55555592";
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, document_number, status)
                VALUES (?, ?, NULL, 'Del User', ?, 'DELETED')
                """, deletedId, "del-" + deletedId + "@t.com", dni);

        String body = """
                {"fullName":"Nuevo","dni":"%s","birthDate":"%s",
                 "email":"nuevo@dev.local","canInvite":false}
                """.formatted(dni, LocalDate.now().minusYears(30));

        HttpResponse<String> resp = post("/api/v1/family/lots/" + lot + "/members", tokenOwner(), body);

        assertThat(resp.statusCode()).isEqualTo(409);
        assertThat(resp.body()).contains("\"code\":\"USER_DELETED\"");
        assertThat(resp.body()).doesNotContain("internal-error");
    }

    // Gate del titular: OWNER de lot no puede agregar en otherLot.
    @Test
    void tituLarSobreLoteAjenoDevuelve403() throws Exception {
        String bd = LocalDate.now().minusYears(30).toString();
        String body = """
                {"fullName":"Maria","dni":"55555558","birthDate":"%s",
                 "email":"maria@dev.local","relation":"Hija","canInvite":true}
                """.formatted(bd);

        HttpResponse<String> resp = post("/api/v1/family/lots/" + otherLot + "/members", tokenOwner(), body);

        assertThat(resp.statusCode()).isEqualTo(403);
        assertThat(resp.body()).contains("forbidden");
    }
}
