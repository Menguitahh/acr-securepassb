package com.securepass.modules.identity.infrastructure.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.notification.application.EmailSender;
import com.securepass.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * securepassb#121 (opcion 3): la suspension es global pero registra que
 * barrio la puso y por que. Ese barrio la levanta; otro barrio ve el motivo
 * en el 409 del alta y pide la revision por email.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserSuspensionHttpTest {

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
    @Autowired ObjectMapper json;
    @MockBean EmailSender emailSender;

    final HttpClient http = HttpClient.newHttpClient();
    UUID robles;
    UUID acacias;
    UUID lot;
    UUID adminRobles;
    UUID adminAcacias;
    String adminRoblesEmail;
    UUID worker;
    String workerDni;
    UUID resident;

    @BeforeEach
    void seed() {
        robles = UUID.randomUUID();
        acacias = UUID.randomUUID();
        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'Los Robles', ?)", robles, "r-" + robles);
        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'Las Acacias', ?)", acacias, "a-" + acacias);
        lot = UUID.randomUUID();
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'A-1')", lot, robles);

        adminRoblesEmail = "adm-" + UUID.randomUUID() + "@r.com";
        adminRobles = user(adminRoblesEmail, "Admin Robles", "h");
        member(adminRobles, robles, "ADMIN");
        adminAcacias = user("adm-" + UUID.randomUUID() + "@a.com", "Admin Acacias", "h");
        member(adminAcacias, acacias, "ADMIN");

        workerDni = String.valueOf(ThreadLocalRandom.current().nextInt(20_000_000, 49_999_999));
        worker = user("w-" + UUID.randomUUID() + "@t.com", "Carlos Plomero", "h");
        jdbc.update("UPDATE users SET document_number = ? WHERE id = ?", workerDni, worker);
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'WORKER', jsonb_build_object('service','Jardin','dailyFrom','08:00','dailyUntil','18:00',
                        'weekdays', jsonb_build_array(1,2,3,4,5)), TRUE)
                """, worker, lot);
        jdbc.update("INSERT INTO worker_tokens (user_id, neighborhood_id, jti) VALUES (?, ?, ?)",
                worker, robles, UUID.randomUUID());

        resident = user("res-" + UUID.randomUUID() + "@t.com", "Ana Residente", "h");
        member(resident, robles, "RESIDENT_OWNER");
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'OWNER', jsonb_build_object('can_invite', TRUE, 'relation', 'OWNER'), TRUE)
                """, resident, lot);
        invitation(resident, true);
        invitation(resident, false);
    }

    @Test
    void suspenderCortaElQrDelTrabajadorYGuardaBarrioYMotivo() throws Exception {
        assertThat(post("/admin/users/" + worker + "/suspend", adminRobles, robles,
                "{\"reason\":\"Robo en el lote A-1\"}").statusCode()).isEqualTo(204);

        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, worker))
                .isEqualTo("SUSPENDED");
        assertThat(jdbc.queryForObject("SELECT suspended_by_neighborhood FROM users WHERE id = ?", UUID.class, worker))
                .isEqualTo(robles);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM worker_tokens WHERE user_id = ? AND revoked_at IS NULL", Integer.class, worker))
                .isZero();
    }

    @Test
    void suspenderAlResidenteRevocaSuPaseYNoLasInvitacionesDeSusVisitas() throws Exception {
        assertThat(post("/admin/users/" + resident + "/suspend", adminRobles, robles,
                "{\"reason\":\"Pedido judicial\"}").statusCode()).isEqualTo(204);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM invitations WHERE created_by = ? AND resident_pass = TRUE", String.class, resident))
                .isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM invitations WHERE created_by = ? AND resident_pass = FALSE", String.class, resident))
                .isEqualTo("ACTIVE");
    }

    @Test
    void otroBarrioVeElMotivoPideRevisionYNoPuedeLevantarla() throws Exception {
        post("/admin/users/" + worker + "/suspend", adminRobles, robles, "{\"reason\":\"Robo en el lote A-1\"}");

        HttpResponse<String> alta = post("/admin/users", adminAcacias, acacias, """
                {"email":"carlos@t.com","fullName":"Carlos","dni":"%s","role":"GUARD"}
                """.formatted(workerDni));
        assertThat(alta.statusCode()).as(alta.body()).isEqualTo(409);
        JsonNode body = json.readTree(alta.body());
        assertThat(body.get("code").asText()).isEqualTo("USER_SUSPENDED");
        assertThat(body.get("existingUserId").asText()).isEqualTo(worker.toString());
        assertThat(body.at("/suspension/neighborhoodName").asText()).isEqualTo("Los Robles");
        assertThat(body.at("/suspension/reason").asText()).isEqualTo("Robo en el lote A-1");
        assertThat(body.at("/suspension/ownNeighborhood").asBoolean()).isFalse();

        assertThat(post("/admin/users/" + worker + "/lift-suspension", adminAcacias, acacias, "").statusCode())
                .isEqualTo(409);

        assertThat(post("/admin/users/" + worker + "/suspension-review", adminAcacias, acacias,
                "{\"message\":\"Quiere trabajar en un lote nuestro\"}").statusCode()).isEqualTo(202);
        ArgumentCaptor<EmailSender.EmailMessage> mail = ArgumentCaptor.forClass(EmailSender.EmailMessage.class);
        verify(emailSender, timeout(3000)).send(mail.capture());
        assertThat(mail.getValue().to()).isEqualTo(adminRoblesEmail);
        assertThat(mail.getValue().subject()).contains("Carlos Plomero");
        assertThat(mail.getValue().textBody()).contains("Admin Acacias", "Las Acacias", "Quiere trabajar en un lote nuestro");

        // Un pedido por persona y barrio cada 24 h.
        assertThat(post("/admin/users/" + worker + "/suspension-review", adminAcacias, acacias, "{}").statusCode())
                .isEqualTo(429);
    }

    @Test
    void elBarrioQueLaPusoLaLevanta() throws Exception {
        post("/admin/users/" + worker + "/suspend", adminRobles, robles, "{\"reason\":\"Robo en el lote A-1\"}");

        HttpResponse<String> found = get("/admin/users?q=plom", adminRobles, robles);
        assertThat(found.statusCode()).isEqualTo(200);
        JsonNode person = json.readTree(found.body()).get(0);
        assertThat(person.get("status").asText()).isEqualTo("SUSPENDED");
        assertThat(person.at("/suspension/ownNeighborhood").asBoolean()).isTrue();
        assertThat(person.get("lotCodes").get(0).asText()).isEqualTo("A-1");

        assertThat(post("/admin/users/" + worker + "/lift-suspension", adminRobles, robles, "").statusCode())
                .isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, worker))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT suspended_reason FROM users WHERE id = ?", String.class, worker))
                .isNull();
    }

    @Test
    void levantarAQuienNuncaActivoLoDejaPendiente() throws Exception {
        jdbc.update("UPDATE users SET status = 'PENDING_ACTIVATION', password_hash = NULL WHERE id = ?", worker);
        post("/admin/users/" + worker + "/suspend", adminRobles, robles, "{\"reason\":\"Robo en el lote A-1\"}");
        post("/admin/users/" + worker + "/lift-suspension", adminRobles, robles, "");
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, worker))
                .isEqualTo("PENDING_ACTIVATION");
    }

    @Test
    void validaciones() throws Exception {
        // Persona de otro barrio: no se confirma que existe.
        assertThat(post("/admin/users/" + worker + "/suspend", adminAcacias, acacias,
                "{\"reason\":\"Robo en el lote\"}").statusCode()).isEqualTo(404);
        assertThat(post("/admin/users/" + adminRobles + "/suspend", adminRobles, robles,
                "{\"reason\":\"Prueba de autosuspension\"}").statusCode()).isEqualTo(400);
        assertThat(post("/admin/users/" + worker + "/suspend", adminRobles, robles,
                "{\"reason\":\"no\"}").statusCode()).isEqualTo(400);
        // El residente no administra suspensiones.
        assertThat(post("/admin/users/" + worker + "/suspend", resident, robles,
                "{\"reason\":\"Robo en el lote\"}", Role.RESIDENT_OWNER).statusCode()).isEqualTo(403);
    }

    private UUID user(String email, String name, String hash) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
                id, email, hash, name);
        return id;
    }

    private void member(UUID userId, UUID neighborhood, String role) {
        jdbc.update("INSERT INTO memberships (user_id, neighborhood_id, role, active) VALUES (?, ?, ?, TRUE)",
                userId, neighborhood, role);
    }

    private void invitation(UUID createdBy, boolean residentPass) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO invitations (id, neighborhood_id, lot_id, created_by, type, status,
                    guest_name, guest_document, companions, valid_from, valid_until,
                    max_uses, used_count, token_kid, jti, resident_pass)
                VALUES (?, ?, ?, ?, 'SINGLE', 'ACTIVE', 'X', '30123456', 0, ?, ?, 1, 0, 'kid', ?, ?)
                """, UUID.randomUUID(), robles, lot, createdBy,
                Timestamp.from(now.minusSeconds(60)), Timestamp.from(now.plusSeconds(3600)),
                UUID.randomUUID(), residentPass);
    }

    private HttpResponse<String> post(String path, UUID actor, UUID neighborhood, String body) throws Exception {
        return post(path, actor, neighborhood, body, Role.ADMIN);
    }

    private HttpResponse<String> post(String path, UUID actor, UUID neighborhood, String body, Role role)
            throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Authorization", "Bearer " + jwt.issue(actor, neighborhood, List.of(role), List.of()).token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, UUID actor, UUID neighborhood) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Authorization", "Bearer " + jwt.issue(actor, neighborhood, List.of(Role.ADMIN), List.of()).token())
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
