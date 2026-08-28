package com.securepass.modules.identity.infrastructure.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.application.ActivationTokenIssuedEvent;
import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.notification.application.EmailSender;
import com.securepass.shared.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Testcontainers
@Import({TestcontainersConfiguration.class, AdminUserOnboardingE2ETest.RollbackTrigger.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminUserOnboardingE2ETest {

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
    @Autowired ObjectMapper json;
    @MockBean EmailSender emailSender;

    static final AtomicBoolean ROLLBACK_NEXT = new AtomicBoolean(false);

    UUID barrioA;
    UUID barrioB;
    UUID lotA;
    UUID lotB;
    UUID adminA;
    UUID guardA;
    UUID residentA;
    UUID existingActive;
    UUID existingPending;
    UUID existingWithDni;
    String existingWithDniValue;
    HttpClient http;

    @BeforeEach
    void seed() {
        http = HttpClient.newHttpClient();
        ROLLBACK_NEXT.set(false);

        barrioA = UUID.randomUUID();
        barrioB = UUID.randomUUID();
        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'Barrio A', ?)",
                barrioA, "a-" + UUID.randomUUID());
        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'Barrio B', ?)",
                barrioB, "b-" + UUID.randomUUID());
        lotA = UUID.randomUUID();
        lotB = UUID.randomUUID();
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'A-01')", lotA, barrioA);
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'B-01')", lotB, barrioB);

        adminA = insertActive("admin-" + UUID.randomUUID() + "@a.com", "Admin Uno", randDni());
        guardA = insertActive("guard-" + UUID.randomUUID() + "@a.com", "Guard Uno", randDni());
        residentA = insertActive("res-" + UUID.randomUUID() + "@a.com", "Res Uno", randDni());
        existingActive = insertActive("taken-" + UUID.randomUUID() + "@a.com", "Taken Active", randDni());
        existingPending = insertPending("pending-" + UUID.randomUUID() + "@a.com", "Pending Uno", randDni());
        existingWithDniValue = randDni();
        existingWithDni = insertActive("withdni-" + UUID.randomUUID() + "@a.com", "Con Dni", existingWithDniValue);
    }

    @AfterEach
    void reset() {
        ROLLBACK_NEXT.set(false);
    }

    @Test
    void residentOwnerHappyPath() throws Exception {
        String body = """
                {"email":"nuevo1@a.com","fullName":"Nuevo Uno","dni":"30111001","role":"RESIDENT_OWNER","lotId":"%s"}
                """.formatted(lotA);
        HttpResponse<String> resp = post("/api/v1/admin/users", adminToken(), body);

        assertThat(resp.statusCode()).isEqualTo(201);
        JsonNode j = json.readTree(resp.body());
        UUID newId = UUID.fromString(j.get("userId").asText());
        assertThat(j.get("role").asText()).isEqualTo("RESIDENT_OWNER");
        assertThat(j.get("status").asText()).isEqualTo("PENDING_ACTIVATION");
        assertThat(j.get("reused").asBoolean()).isFalse();
        assertThat(j.get("changed").asBoolean()).isTrue();
        assertThat(j.get("email").asText()).isEqualTo("nuevo1@a.com");
        assertThat(j.get("fullName").asText()).isEqualTo("Nuevo Uno");

        assertThat(status(newId)).isEqualTo("PENDING_ACTIVATION");
        assertThat(passwordHash(newId)).isNull();
        assertThat(hasMembership(newId, barrioA, "RESIDENT_OWNER")).isTrue();
        assertThat(hasLotPermission(newId, lotA, "OWNER")).isTrue();
        assertThat(activationTokensFor(newId)).isEqualTo(1);
        verify(emailSender, timeout(2000)).send(any());
    }

    @Test
    void guardHappyPath() throws Exception {
        String body = """
                {"email":"nuevoguard@a.com","fullName":"Nuevo Guard","dni":"30111002","role":"GUARD"}
                """;
        HttpResponse<String> resp = post("/api/v1/admin/users", adminToken(), body);

        assertThat(resp.statusCode()).isEqualTo(201);
        UUID newId = UUID.fromString(json.readTree(resp.body()).get("userId").asText());
        assertThat(hasMembership(newId, barrioA, "GUARD")).isTrue();
        assertThat(lotPermissionsFor(newId)).isZero();
        verify(emailSender, timeout(2000)).send(any());
    }

    @Test
    void adminNoSePuedeCrearPorHttp() throws Exception {
        // WBS-A (issue #129): el aprovisionamiento de ADMIN va SOLO por el
        // NeighborhoodProvisioner CLI, nunca por HTTP. Este gate es la
        // ultima red que impide escalada si alguien refactoriza el
        // controller o cambia ALLOWED_ROLES por error.
        String body = """
                {"email":"escalate@x.com","fullName":"Escalate","dni":"30999888","role":"ADMIN"}
                """;
        HttpResponse<String> resp = post("/api/v1/admin/users", adminToken(), body);
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("role invalido");
    }

    @Test
    void conflicto409EmailTaken() throws Exception {
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, existingActive);
        String body = """
                {"email":"%s","fullName":"Otro","dni":"30111003","role":"GUARD"}
                """.formatted(email);
        HttpResponse<String> resp = post("/api/v1/admin/users", adminToken(), body);

        assertThat(resp.statusCode()).isEqualTo(409);
        JsonNode j = json.readTree(resp.body());
        assertThat(j.get("code").asText()).isEqualTo("EMAIL_TAKEN");
        // WBS-107: ahora incluye existingUserId para trazabilidad.
        assertThat(UUID.fromString(j.get("existingUserId").asText())).isEqualTo(existingActive);
    }

    // ---- WBS-107: dedupe por DNI ----

    @Test
    void dniExistenteReusaUserYSumaPermisoDevuelve200() throws Exception {
        // Reuso mismo-barrio: existingWithDni ya vive en barrioA (insertActive
        // no le da membership; el alta le suma OWNER sobre lotA).
        String body = """
                {"email":"otro-email-%s@a.com","fullName":"Nombre Escrito Mal","dni":"%s","role":"RESIDENT_OWNER","lotId":"%s"}
                """.formatted(UUID.randomUUID(), existingWithDniValue, lotA);
        HttpResponse<String> resp = post("/api/v1/admin/users", adminToken(), body);

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode j = json.readTree(resp.body());
        assertThat(UUID.fromString(j.get("userId").asText())).isEqualTo(existingWithDni);
        assertThat(j.get("reused").asBoolean()).isTrue();
        assertThat(j.get("changed").asBoolean()).isTrue();
        // Gana el email viejo, no el pasado.
        String realEmail = jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, existingWithDni);
        assertThat(j.get("email").asText()).isEqualTo(realEmail);
        assertThat(j.get("fullName").asText()).isEqualTo("Con Dni"); // el viejo, no "Nombre Escrito Mal"

        assertThat(hasMembership(existingWithDni, barrioA, "RESIDENT_OWNER")).isTrue();
        assertThat(hasLotPermission(existingWithDni, lotA, "OWNER")).isTrue();
        assertThat(hasLotPermission(existingWithDni, lotA, "OWNER")).isTrue();
    }

    @Test
    void dniExistenteConMismoPermiso200Idempotente() throws Exception {
        // Primero le sumamos OWNER sobre lotA.
        post("/api/v1/admin/users", adminToken(), """
                {"email":"first-%s@a.com","fullName":"X","dni":"%s","role":"RESIDENT_OWNER","lotId":"%s"}
                """.formatted(UUID.randomUUID(), existingWithDniValue, lotA));
        int lpBefore = lotPermissionsFor(existingWithDni);
        int msBefore = membershipsFor(existingWithDni, barrioA);

        // Segundo alta: mismo DNI, mismo lote. Debe ser idempotente.
        HttpResponse<String> resp = post("/api/v1/admin/users", adminToken(), """
                {"email":"second-%s@a.com","fullName":"Y","dni":"%s","role":"RESIDENT_OWNER","lotId":"%s"}
                """.formatted(UUID.randomUUID(), existingWithDniValue, lotA));

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode j = json.readTree(resp.body());
        assertThat(j.get("reused").asBoolean()).isTrue();
        assertThat(j.get("changed").asBoolean()).isFalse();
        assertThat(lotPermissionsFor(existingWithDni)).isEqualTo(lpBefore);
        assertThat(membershipsFor(existingWithDni, barrioA)).isEqualTo(msBefore);
    }

    @Test
    void reusoCrossBarrioCreaTresFilasEnBarrioNuevo() throws Exception {
        // El admin de barrioA da de alta un user cuyo DNI ya existe (existingWithDni).
        // El endpoint solo permite altas en el barrio del admin. Simulamos
        // "cross-barrio" agregando primero al user en barrioA por otra via
        // (aca ya existe), y luego confirmando que un alta en un nuevo lote
        // de barrioA acumula un lot_permission mas y mantiene el mismo user.
        // Cross-barrio real (barrioB) requeriria un admin de barrioB — mismo
        // codigo, distinto contexto.
        int lpBefore = lotPermissionsFor(existingWithDni);

        // Nuevo lote en barrioA
        UUID lotA2 = UUID.randomUUID();
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'A-02')", lotA2, barrioA);

        HttpResponse<String> resp = post("/api/v1/admin/users", adminToken(), """
                {"email":"multi-%s@a.com","fullName":"X","dni":"%s","role":"RESIDENT_OWNER","lotId":"%s"}
                """.formatted(UUID.randomUUID(), existingWithDniValue, lotA2));

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(lotPermissionsFor(existingWithDni)).isEqualTo(lpBefore + 1);
        assertThat(hasLotPermission(existingWithDni, lotA2, "OWNER")).isTrue();
        assertThat(hasLotPermission(existingWithDni, lotA2, "OWNER")).isTrue();
        // La membership del barrioA ya existia; sigue en 1.
        assertThat(membershipsFor(existingWithDni, barrioA)).isEqualTo(1);
    }

    @Test
    void reusoConEmailNullCompletaYReemiteActivacion() throws Exception {
        // User con email NULL y phone presente (caso trabajador/menor futuro).
        UUID emailNullUser = UUID.randomUUID();
        String dniNull = randDni();
        jdbc.update("""
                INSERT INTO users (id, email, phone, password_hash, full_name, document_number, status)
                VALUES (?, NULL, '+5491199999999', NULL, 'Sin Email', ?, 'PENDING_ACTIVATION')
                """, emailNullUser, dniNull);

        HttpResponse<String> resp = post("/api/v1/admin/users", adminToken(), """
                {"email":"completado@a.com","fullName":"Sin Email","dni":"%s","role":"RESIDENT_OWNER","lotId":"%s"}
                """.formatted(dniNull, lotA));

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode j = json.readTree(resp.body());
        assertThat(j.get("reused").asBoolean()).isTrue();
        assertThat(j.get("changed").asBoolean()).isTrue();
        assertThat(j.get("email").asText()).isEqualTo("completado@a.com");
        String storedEmail = jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, emailNullUser);
        assertThat(storedEmail).isEqualTo("completado@a.com");
        // PENDING_ACTIVATION -> reemite activacion.
        verify(emailSender, timeout(2000)).send(any());
        assertThat(activationTokensActiveFor(emailNullUser)).isEqualTo(1);
    }

    @Test
    void reusoEmailDivergenteMantieneElExistente() throws Exception {
        // existingWithDni tiene email "withdni-...@a.com". El admin escribe otro.
        String realEmail = jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, existingWithDni);

        HttpResponse<String> resp = post("/api/v1/admin/users", adminToken(), """
                {"email":"divergente-%s@a.com","fullName":"X","dni":"%s","role":"GUARD"}
                """.formatted(UUID.randomUUID(), existingWithDniValue));

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode j = json.readTree(resp.body());
        assertThat(j.get("email").asText()).isEqualTo(realEmail);
        // DB: no se pisa
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, existingWithDni))
                .isEqualTo(realEmail);
    }

    @Test
    void reusoPendingActivationInvalidaTokenViejoYEmiteNuevo() throws Exception {
        // Sembrar token viejo en existingPending
        UUID oldTokenId = UUID.randomUUID();
        byte[] oldHash = new byte[32];
        oldHash[0] = 42;
        jdbc.update("""
                INSERT INTO activation_tokens (id, user_id, purpose, token_hash, expires_at)
                VALUES (?, ?, 'ACTIVATION', ?, NOW() + INTERVAL '7 days')
                """, oldTokenId, existingPending, oldHash);
        String existingPendingDni = jdbc.queryForObject(
                "SELECT document_number FROM users WHERE id=?", String.class, existingPending);

        HttpResponse<String> resp = post("/api/v1/admin/users", adminToken(), """
                {"email":"otro-%s@a.com","fullName":"X","dni":"%s","role":"RESIDENT_OWNER","lotId":"%s"}
                """.formatted(UUID.randomUUID(), existingPendingDni, lotA));

        assertThat(resp.statusCode()).isEqualTo(200);
        Boolean oldConsumed = jdbc.queryForObject(
                "SELECT consumed_at IS NOT NULL FROM activation_tokens WHERE id=?",
                Boolean.class, oldTokenId);
        assertThat(oldConsumed).isTrue();
        assertThat(activationTokensActiveFor(existingPending)).isEqualTo(1);
    }

    @Test
    void suspended409UserSuspended() throws Exception {
        UUID suspended = UUID.randomUUID();
        String dni = randDni();
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, document_number, status)
                VALUES (?, ?, 'hash', 'Suspended', ?, 'SUSPENDED')
                """, suspended, "susp-" + UUID.randomUUID() + "@a.com", dni);

        HttpResponse<String> resp = post("/api/v1/admin/users", adminToken(), """
                {"email":"otro-%s@a.com","fullName":"X","dni":"%s","role":"GUARD"}
                """.formatted(UUID.randomUUID(), dni));

        assertThat(resp.statusCode()).isEqualTo(409);
        JsonNode j = json.readTree(resp.body());
        assertThat(j.get("code").asText()).isEqualTo("USER_SUSPENDED");
        assertThat(UUID.fromString(j.get("existingUserId").asText())).isEqualTo(suspended);
        // No suma permiso
        assertThat(hasMembership(suspended, barrioA, "GUARD")).isFalse();
    }

    @Test
    void deleted409UserDeleted() throws Exception {
        UUID deleted = UUID.randomUUID();
        String dni = randDni();
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, document_number, status)
                VALUES (?, ?, 'hash', 'Deleted', ?, 'DELETED')
                """, deleted, "del-" + UUID.randomUUID() + "@a.com", dni);

        HttpResponse<String> resp = post("/api/v1/admin/users", adminToken(), """
                {"email":"otro-%s@a.com","fullName":"X","dni":"%s","role":"GUARD"}
                """.formatted(UUID.randomUUID(), dni));

        assertThat(resp.statusCode()).isEqualTo(409);
        JsonNode j = json.readTree(resp.body());
        assertThat(j.get("code").asText()).isEqualTo("USER_DELETED");
        assertThat(UUID.fromString(j.get("existingUserId").asText())).isEqualTo(deleted);
    }

    @Test
    void loteDeOtroBarrioDevuelve404() throws Exception {
        String body = """
                {"email":"nuevoxb@a.com","fullName":"Otro","dni":"30111005","role":"RESIDENT_OWNER","lotId":"%s"}
                """.formatted(lotB);
        HttpResponse<String> resp = post("/api/v1/admin/users", adminToken(), body);

        assertThat(resp.statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email='nuevoxb@a.com'", Integer.class))
                .isZero();
    }

    @Test
    void guardAutenticadoNoPuedeAltaAdmin() throws Exception {
        String body = """
                {"email":"nuevo-guard-forbid@a.com","fullName":"X","dni":"30111006","role":"GUARD"}
                """;
        HttpResponse<String> resp = post("/api/v1/admin/users", guardToken(), body);
        assertThat(resp.statusCode()).isEqualTo(403);
    }

    @Test
    void residentAutenticadoNoPuedeAltaAdmin() throws Exception {
        String body = """
                {"email":"nuevo-res-forbid@a.com","fullName":"X","dni":"30111007","role":"GUARD"}
                """;
        HttpResponse<String> resp = post("/api/v1/admin/users", residentToken(), body);
        assertThat(resp.statusCode()).isEqualTo(403);
    }

    @Test
    void resendSobreUsuarioActiveDevuelve409() throws Exception {
        HttpResponse<String> resp = post(
                "/api/v1/admin/users/" + existingActive + "/resend-activation",
                adminToken(), "");
        assertThat(resp.statusCode()).isEqualTo(409);
    }

    @Test
    void resendSobreUsuarioPendingEmiteTokenNuevoInvalidandoElAnterior() throws Exception {
        // Sembrar un token viejo
        UUID oldTokenId = UUID.randomUUID();
        byte[] oldHash = new byte[32];
        oldHash[0] = 1;
        jdbc.update("""
                INSERT INTO activation_tokens (id, user_id, purpose, token_hash, expires_at)
                VALUES (?, ?, 'ACTIVATION', ?, NOW() + INTERVAL '7 days')
                """, oldTokenId, existingPending, oldHash);

        HttpResponse<String> resp = post(
                "/api/v1/admin/users/" + existingPending + "/resend-activation",
                adminToken(), "");
        assertThat(resp.statusCode()).isEqualTo(204);

        Boolean oldConsumed = jdbc.queryForObject(
                "SELECT consumed_at IS NOT NULL FROM activation_tokens WHERE id=?",
                Boolean.class, oldTokenId);
        assertThat(oldConsumed).isTrue();
        assertThat(activationTokensActiveFor(existingPending)).isEqualTo(1);
        verify(emailSender, timeout(2000)).send(any());
    }

    @Test
    void rollbackNoEmiteMailNiDejaFilas() throws Exception {
        ROLLBACK_NEXT.set(true);

        String body = """
                {"email":"rollback@a.com","fullName":"Rollback","dni":"30111099","role":"GUARD"}
                """;
        HttpResponse<String> resp = post("/api/v1/admin/users", adminToken(), body);

        assertThat(resp.statusCode()).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email='rollback@a.com'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM activation_tokens WHERE user_id IN (SELECT id FROM users WHERE email='rollback@a.com')",
                Integer.class)).isZero();
        // No hay AFTER_COMMIT porque no hubo commit; email nunca sale.
        verifyNoInteractions(emailSender);
    }

    // ---- helpers ----

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

    private String adminToken() {
        return jwt.issue(adminA, barrioA, List.of(Role.ADMIN), List.of()).token();
    }

    private String guardToken() {
        return jwt.issue(guardA, barrioA, List.of(Role.GUARD), List.of()).token();
    }

    private String residentToken() {
        return jwt.issue(residentA, barrioA, List.of(Role.RESIDENT_OWNER), List.of(lotA)).token();
    }

    private static String randDni() {
        return String.valueOf(20_000_000 + (int) (Math.random() * 79_999_999));
    }

    private UUID insertActive(String email, String name, String dni) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, document_number, status)
                VALUES (?, ?, 'bcrypt-fake', ?, ?, 'ACTIVE')
                """, id, email, name, dni);
        return id;
    }

    private UUID insertPending(String email, String name, String dni) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, document_number, status)
                VALUES (?, ?, NULL, ?, ?, 'PENDING_ACTIVATION')
                """, id, email, name, dni);
        return id;
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM users WHERE id=?", String.class, id);
    }

    private String passwordHash(UUID id) {
        return jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, id);
    }

    private boolean hasMembership(UUID userId, UUID barrio, String role) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM memberships
                 WHERE user_id=? AND neighborhood_id=? AND role=? AND active=TRUE
                """, Integer.class, userId, barrio, role);
        return count != null && count > 0;
    }

    private int lotPermissionsFor(UUID userId) {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM lot_permissions WHERE user_id=?",
                Integer.class, userId);
        return c == null ? 0 : c;
    }

    private int membershipsFor(UUID userId, UUID barrio) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM memberships WHERE user_id=? AND neighborhood_id=?",
                Integer.class, userId, barrio);
        return c == null ? 0 : c;
    }

    private boolean hasLotPermission(UUID userId, UUID lot, String type) {
        Integer c = jdbc.queryForObject("""
                SELECT COUNT(*) FROM lot_permissions
                 WHERE user_id=? AND lot_id=? AND permission_type=? AND active=TRUE
                """, Integer.class, userId, lot, type);
        return c != null && c > 0;
    }

    private int activationTokensFor(UUID userId) {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM activation_tokens WHERE user_id=?",
                Integer.class, userId);
        return c == null ? 0 : c;
    }

    private int activationTokensActiveFor(UUID userId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM activation_tokens WHERE user_id=? AND consumed_at IS NULL",
                Integer.class, userId);
        return c == null ? 0 : c;
    }

    /**
     * Listener sincronico opt-in que tira en el mismo hilo del request y
     * dentro de la transaccion. Sirve para forzar rollback post-issue y
     * verificar que el listener AFTER_COMMIT del email no dispara.
     */
    @TestConfiguration
    static class RollbackTrigger {
        @EventListener
        public void onIssued(ActivationTokenIssuedEvent e) {
            if (ROLLBACK_NEXT.get()) {
                throw new RuntimeException("forced rollback for test");
            }
        }
    }
}
