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
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #181: el titular corrige el email de un trabajador que no activo su
 * cuenta. Solo si es OWNER de todos los lotes de esa persona: si no, otro
 * titular podria quedarse con la activacion.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChangeWorkerEmailHttpTest {

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

    final HttpClient http = HttpClient.newHttpClient();
    UUID barrio;
    UUID lot;
    UUID otherLot;
    UUID owner;
    UUID otherOwner;
    UUID worker;

    @BeforeEach
    void seed() {
        barrio = UUID.randomUUID();
        lot = UUID.randomUUID();
        otherLot = UUID.randomUUID();
        owner = UUID.randomUUID();
        otherOwner = UUID.randomUUID();
        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'B', ?)", barrio, "b-" + barrio);
        for (UUID l : List.of(lot, otherLot)) {
            jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, ?)",
                    l, barrio, "L-" + l.toString().substring(0, 6));
        }
        for (UUID[] o : new UUID[][] {{owner, lot}, {otherOwner, otherLot}}) {
            jdbc.update("INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, 'h', 'Titular', 'ACTIVE')",
                    o[0], "o-" + o[0] + "@t.com");
            jdbc.update("INSERT INTO memberships (user_id, neighborhood_id, role, active) VALUES (?, ?, 'RESIDENT_OWNER', TRUE)",
                    o[0], barrio);
            jdbc.update("INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active) VALUES (?, ?, 'OWNER', '{}'::jsonb, TRUE)",
                    o[0], o[1]);
        }
        worker = pendingWorker("typo-" + UUID.randomUUID() + "@ejmplo.com", lot);
    }

    private UUID pendingWorker(String email, UUID onLot) {
        UUID id = UUID.randomUUID();
        String dni = String.valueOf(20_000_000 + ThreadLocalRandom.current().nextInt(9_000_000));
        jdbc.update("INSERT INTO users (id, email, full_name, document_number, status) VALUES (?, ?, 'Juan', ?, 'PENDING_ACTIVATION')",
                id, email, dni);
        workerOn(id, onLot);
        return id;
    }

    private void workerOn(UUID userId, UUID onLot) {
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'WORKER', jsonb_build_object('service', 'Jardineria', 'weekdays', '[1]'::jsonb,
                        'dailyFrom', '08:00', 'dailyUntil', '12:00'), TRUE)
                """, userId, onLot);
    }

    private HttpResponse<String> put(UUID onLot, UUID userId, String email) throws Exception {
        String token = jwt.issue(owner, barrio, List.of(Role.RESIDENT_OWNER), List.of(lot)).token();
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/lots/" + onLot + "/workers/" + userId + "/email"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .PUT(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + email + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private String emailOf(UUID userId) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private int activeTokens(UUID userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM activation_tokens WHERE user_id = ? AND consumed_at IS NULL", Integer.class, userId);
    }

    @Test
    void corrigeElEmailYReenviaLaActivacion() throws Exception {
        String nuevo = "juan-" + UUID.randomUUID() + "@ejemplo.com";

        HttpResponse<String> resp = put(lot, worker, nuevo.toUpperCase());

        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        assertThat(emailOf(worker)).isEqualTo(nuevo);
        assertThat(activeTokens(worker)).isEqualTo(1);

        // Un segundo cambio invalida el link anterior: queda uno solo vivo.
        assertThat(put(lot, worker, "otro-" + nuevo).statusCode()).isEqualTo(200);
        assertThat(activeTokens(worker)).isEqualTo(1);
    }

    @Test
    void agregaEmailAUnTrabajadorDadoDeAltaSoloConTelefono() throws Exception {
        UUID sinEmail = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, phone, full_name, document_number, status) VALUES (?, '2615550000', 'Pedro', ?, 'PENDING_ACTIVATION')",
                sinEmail, String.valueOf(10_000_000 + ThreadLocalRandom.current().nextInt(9_000_000)));
        workerOn(sinEmail, lot);
        String email = "pedro-" + UUID.randomUUID() + "@ejemplo.com";

        assertThat(put(lot, sinEmail, email).statusCode()).isEqualTo(200);
        assertThat(emailOf(sinEmail)).isEqualTo(email);
        assertThat(activeTokens(sinEmail)).isEqualTo(1);
    }

    @Test
    void siTambienTrabajaEnElLoteDeOtroTitularEs409YNoCambia() throws Exception {
        workerOn(worker, otherLot);
        String antes = emailOf(worker);

        HttpResponse<String> resp = put(lot, worker, "mio-" + UUID.randomUUID() + "@t.com");

        assertThat(resp.statusCode()).isEqualTo(409);
        assertThat(resp.body()).contains("EMAIL_NOT_EDITABLE");
        assertThat(emailOf(worker)).isEqualTo(antes);
    }

    @Test
    void cuentaYaActivaEs409() throws Exception {
        jdbc.update("UPDATE users SET status = 'ACTIVE', password_hash = 'h' WHERE id = ?", worker);

        HttpResponse<String> resp = put(lot, worker, "x-" + UUID.randomUUID() + "@t.com");

        assertThat(resp.statusCode()).isEqualTo(409);
        assertThat(resp.body()).contains("USER_ALREADY_ACTIVE");
    }

    @Test
    void emailDeOtraPersonaEs409() throws Exception {
        HttpResponse<String> resp = put(lot, worker, "o-" + otherOwner + "@t.com");

        assertThat(resp.statusCode()).isEqualTo(409);
        assertThat(resp.body()).contains("EMAIL_TAKEN");
    }

    @Test
    void trabajadorDeOtroLoteEs404YLoteAjenoEs403() throws Exception {
        UUID ajeno = pendingWorker("a-" + UUID.randomUUID() + "@t.com", otherLot);

        assertThat(put(lot, ajeno, "b-" + UUID.randomUUID() + "@t.com").statusCode()).isEqualTo(404);
        assertThat(put(otherLot, ajeno, "c-" + UUID.randomUUID() + "@t.com").statusCode()).isEqualTo(403);
    }
}
