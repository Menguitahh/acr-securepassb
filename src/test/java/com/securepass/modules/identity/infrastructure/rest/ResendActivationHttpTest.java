package com.securepass.modules.identity.infrastructure.rest;

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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US-54: reenviar la activacion desde la pantalla donde se creo la cuenta.
 * El titular, para familiares y trabajadores de su lote; el admin, para las
 * cuentas pendientes de su barrio.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResendActivationHttpTest {

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

    final HttpClient http = HttpClient.newHttpClient();
    UUID barrio;
    UUID otroBarrio;
    UUID lot;
    UUID otherLot;
    UUID owner;
    UUID admin;
    UUID worker;

    @BeforeEach
    void seed() {
        barrio = UUID.randomUUID();
        otroBarrio = UUID.randomUUID();
        lot = UUID.randomUUID();
        otherLot = UUID.randomUUID();
        owner = UUID.randomUUID();
        admin = UUID.randomUUID();
        for (UUID n : List.of(barrio, otroBarrio)) {
            jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'B', ?)", n, "b-" + n);
        }
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'L-1')", lot, barrio);
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'L-9')", otherLot, otroBarrio);
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, 'h', 'Titular', 'ACTIVE')",
                owner, "o-" + owner + "@t.com");
        jdbc.update("INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active) VALUES (?, ?, 'OWNER', '{}'::jsonb, TRUE)",
                owner, lot);
        worker = pending("Wanda", lot, "WORKER");
    }

    private UUID pending(String name, UUID onLot, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, full_name, status) VALUES (?, ?, ?, 'PENDING_ACTIVATION')",
                id, name.toLowerCase() + "-" + id + "@t.com", name);
        String attrs = "WORKER".equals(type)
                ? "jsonb_build_object('service','Limpieza','weekdays','[1]'::jsonb,'dailyFrom','08:00','dailyUntil','12:00')"
                : "'{}'::jsonb";
        jdbc.update("INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active) VALUES (?, ?, ?, "
                + attrs + ", TRUE)", id, onLot, type);
        return id;
    }

    private HttpResponse<String> send(String method, String path, UUID actor, List<Role> roles, List<UUID> lots)
            throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + jwt.issue(actor, barrio, roles, lots).token())
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> ownerResend(UUID userId) throws Exception {
        return send("POST", "/api/v1/lots/" + lot + "/members/" + userId + "/resend-activation",
                owner, List.of(Role.RESIDENT_OWNER), List.of(lot));
    }

    private int activeTokens(UUID userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM activation_tokens WHERE user_id = ? AND consumed_at IS NULL", Integer.class, userId);
    }

    @Test
    void elTitularReenviaYQuedaUnSoloLinkVivo() throws Exception {
        assertThat(ownerResend(worker).statusCode()).isEqualTo(204);
        assertThat(ownerResend(worker).statusCode()).isEqualTo(204);
        assertThat(activeTokens(worker)).isEqualTo(1);
    }

    @Test
    void laListaDeTrabajadoresDiceSiEstaPendiente() throws Exception {
        HttpResponse<String> resp = send("GET", "/api/v1/lots/" + lot + "/workers",
                owner, List.of(Role.RESIDENT_OWNER), List.of(lot));
        assertThat(resp.body()).contains("\"userStatus\":\"PENDING_ACTIVATION\"");
    }

    @Test
    void cuentaActivaEs409YPersonaDeOtroLoteEs404() throws Exception {
        jdbc.update("UPDATE users SET status = 'ACTIVE', password_hash = 'h' WHERE id = ?", worker);
        assertThat(ownerResend(worker).statusCode()).isEqualTo(409);

        UUID ajeno = pending("Ajeno", otherLot, "MEMBER");
        assertThat(ownerResend(ajeno).statusCode()).isEqualTo(404);
    }

    @Test
    void elAdminVeLasPendientesDeSuBarrioYNoReenviaAOtro() throws Exception {
        UUID ajeno = pending("Ajeno", otherLot, "MEMBER");

        HttpResponse<String> list = send("GET", "/api/v1/admin/users/pending", admin, List.of(Role.ADMIN), List.of());
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains(worker.toString()).contains("L-1").doesNotContain(ajeno.toString());

        assertThat(send("POST", "/api/v1/admin/users/" + ajeno + "/resend-activation", admin, List.of(Role.ADMIN),
                List.of()).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/api/v1/admin/users/" + worker + "/resend-activation", admin, List.of(Role.ADMIN),
                List.of()).statusCode()).isEqualTo(204);
    }
}
