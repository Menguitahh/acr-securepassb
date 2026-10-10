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
 * WBS-111: end-to-end del padron de menores.
 *
 * Cubre los cuatro endpoints (POST alta, GET list, DELETE, POST graduate)
 * y los limites de seguridad que importan:
 *   - Gate del titular en alta.
 *   - IDOR cross-barrio para el ADMIN en GET (regresion de #135).
 *   - AGE_OVER_16 (mayor de la cota) devuelve redirectTo a /members.
 *   - Graduate rollback: alta fallida (SUSPENDED) mantiene al menor en el
 *     padron; sin rollback la fila se perderia dejando el DNI en users
 *     con el menor huerfano.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FamilyMinorsHttpTest {

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

    UUID barrioA;
    UUID barrioB;
    UUID lotA;
    UUID lotB;
    UUID ownerA;
    UUID adminB;
    HttpClient http;

    @BeforeEach
    void seed() {
        http = HttpClient.newHttpClient();
        barrioA = UUID.randomUUID();
        barrioB = UUID.randomUUID();
        lotA = UUID.randomUUID();
        lotB = UUID.randomUUID();
        ownerA = UUID.randomUUID();
        adminB = UUID.randomUUID();

        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'A', ?)",
                barrioA, "a-" + UUID.randomUUID());
        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'B', ?)",
                barrioB, "b-" + UUID.randomUUID());
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'L-A')",
                lotA, barrioA);
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'L-B')",
                lotB, barrioB);
        seedUser(ownerA, "ownerA");
        seedUser(adminB, "adminB");
        jdbc.update("""
                INSERT INTO memberships (user_id, neighborhood_id, role, active) VALUES
                    (?, ?, 'RESIDENT_OWNER', TRUE),
                    (?, ?, 'ADMIN', TRUE)
                """, ownerA, barrioA, adminB, barrioB);
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'OWNER', '{"can_invite": true, "relation": "OWNER"}'::jsonb, TRUE)
                """, ownerA, lotA);
    }

    private void seedUser(UUID id, String label) {
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, 'h', ?, 'ACTIVE')
                """, id, label + "-" + id + "@t.com", label);
    }

    private String tokenOwnerA() {
        return jwt.issue(ownerA, barrioA, List.of(Role.RESIDENT_OWNER), List.of(lotA)).token();
    }

    private String tokenAdminB() {
        return jwt.issue(adminB, barrioB, List.of(Role.ADMIN), List.of()).token();
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

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path, String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .DELETE()
                .timeout(Duration.ofSeconds(5))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private LocalDate ageYears(int years) {
        return LocalDate.now().minusYears(years).minusDays(1);
    }

    private String altaBody(String dni, LocalDate birth, String nombre) {
        String dniPart = dni == null ? "" : "\"documentNumber\":\"" + dni + "\",";
        return """
                {"fullName":"%s",%s "birthDate":"%s","relation":"Hijo"}
                """.formatted(nombre, dniPart, birth);
    }

    @Test
    void altaHappyPath_devuelve201() throws Exception {
        HttpResponse<String> resp = post(
                "/api/v1/family/lots/" + lotA + "/minors", tokenOwnerA(),
                altaBody("55111222", ageYears(10), "Juan"));
        assertThat(resp.statusCode()).isEqualTo(201);
        assertThat(resp.body()).contains("\"documentNumber\":\"55111222\"");
        assertThat(resp.body()).contains("\"fullName\":\"Juan\"");
    }

    // Mayor de 16 -> AGE_OVER_16 con redirectTo a /members.
    @Test
    void mayorDeLaCotaDevuelve400AgeOver16ConRedirect() throws Exception {
        HttpResponse<String> resp = post(
                "/api/v1/family/lots/" + lotA + "/minors", tokenOwnerA(),
                altaBody("55111223", ageYears(17), "Mayor"));
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("\"code\":\"AGE_OVER_16\"");
        assertThat(resp.body()).contains("\"maxAge\":16");
        assertThat(resp.body()).contains("\"redirectTo\":\"/api/v1/family/lots/" + lotA + "/members\"");
    }

    // Gate: titular de otro lote no puede dar de alta en este.
    @Test
    void tituLarSobreLoteAjenoDevuelve403() throws Exception {
        HttpResponse<String> resp = post(
                "/api/v1/family/lots/" + lotB + "/minors", tokenOwnerA(),
                altaBody("55111224", ageYears(10), "Kid"));
        assertThat(resp.statusCode()).isEqualTo(403);
    }

    // c) IDOR cross-barrio: ADMIN del barrio B pasa el gate del GET (por
    // hasRole('ADMIN')), pero el use case tiene que detenerlo con 403
    // ("recurso de otro barrio"). Mismo criterio que ListFamilyMembersUseCase
    // (#135) — no cambiar sin cambiar tambien /members.
    @Test
    void adminCrossBarrio_GET_devuelve403() throws Exception {
        // Sembrar un menor en el lote A del barrio A.
        post("/api/v1/family/lots/" + lotA + "/minors", tokenOwnerA(),
                altaBody("55111225", ageYears(10), "Chico"));

        HttpResponse<String> resp = get(
                "/api/v1/family/lots/" + lotA + "/minors", tokenAdminB());
        assertThat(resp.statusCode()).isEqualTo(403);
        assertThat(resp.body()).doesNotContain("Chico");
    }

    @Test
    void deleteHardDelete_devuelve204YRemoueveDeLaLista() throws Exception {
        HttpResponse<String> alta = post(
                "/api/v1/family/lots/" + lotA + "/minors", tokenOwnerA(),
                altaBody("55111226", ageYears(10), "Pia"));
        String id = alta.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        HttpResponse<String> del = delete(
                "/api/v1/family/lots/" + lotA + "/minors/" + id, tokenOwnerA());
        assertThat(del.statusCode()).isEqualTo(204);

        HttpResponse<String> list = get("/api/v1/family/lots/" + lotA + "/minors", tokenOwnerA());
        assertThat(list.body()).doesNotContain("Pia");
    }

    // Seed directo por SQL: el menor ya cumplio 16 (se cargo hace un anio con
    // 15). El invariante Minor rechaza age >= 16 en el alta por API, pero el
    // padron acepta filas viejas hasta 17 anios por el CHECK con colchon (ver
    // V13). Este es exactamente el caso donde el titular quiere graduar.
    private UUID seedMinorReadyToGraduate(UUID lotId, UUID barrio, UUID by, String dni, String name) {
        UUID id = UUID.randomUUID();
        LocalDate birth = LocalDate.now().minusYears(16).minusMonths(1);
        jdbc.update("""
                INSERT INTO lot_minors (id, lot_id, neighborhood_id, full_name,
                    document_number, birth_date, relation, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 'Hijo', ?)
                """, id, lotId, barrio, name, dni, java.sql.Date.valueOf(birth), by);
        return id;
    }

    // Graduate happy path: la fila del padron desaparece y aparece un user
    // PENDING_ACTIVATION con el mismo DNI.
    @Test
    void graduateHappyPath_borraDelPadronYCreaUserPending() throws Exception {
        String dni = "55111227";
        UUID minorId = seedMinorReadyToGraduate(lotA, barrioA, ownerA, dni, "AGraduar");
        String id = minorId.toString();

        HttpResponse<String> resp = post(
                "/api/v1/family/lots/" + lotA + "/minors/" + id + "/graduate", tokenOwnerA(),
                """
                {"email":"agraduar@dev.local","relation":"Hijo","canInvite":true}
                """);
        assertThat(resp.statusCode()).isEqualTo(201);
        assertThat(resp.body()).contains("\"graduatedFromMinorId\":\"" + id + "\"");
        assertThat(resp.body()).contains("\"outcome\":\"CREATED_USER_AND_PERMISSION\"");

        Integer minorRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot_minors WHERE id = ?::uuid", Integer.class, id);
        assertThat(minorRows).isZero();
        Integer userRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE document_number = ? AND status = 'PENDING_ACTIVATION'",
                Integer.class, dni);
        assertThat(userRows).isEqualTo(1);
    }

    // Sin DNI en el padron y sin dniOverride en el body -> 422 claro, no 500.
    @Test
    void graduateSinDniNiOverride_devuelve422() throws Exception {
        UUID minorId = seedMinorReadyToGraduate(lotA, barrioA, ownerA, null, "SinDni");
        String id = minorId.toString();

        HttpResponse<String> resp = post(
                "/api/v1/family/lots/" + lotA + "/minors/" + id + "/graduate", tokenOwnerA(),
                """
                {"email":"sindni@dev.local","canInvite":true}
                """);
        assertThat(resp.statusCode()).isEqualTo(422);
        assertThat(resp.body()).contains("minor-dni-required");
        Integer stillThere = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot_minors WHERE id = ?::uuid", Integer.class, id);
        assertThat(stillThere).isEqualTo(1); // NO se borro del padron
    }

    // d) Rollback: alta fallida (user SUSPENDED con mismo DNI) mantiene la
    // fila del padron. Sin @Transactional en graduate, el DELETE se
    // commitearia antes del reventon y el menor desapareceria del padron
    // sin quedar como user.
    @Test
    void graduateAltaFallida_rollbackMantieneMenorEnPadron() throws Exception {
        String dni = "55111228";
        UUID minorId = seedMinorReadyToGraduate(lotA, barrioA, ownerA, dni, "Rollback");
        String id = minorId.toString();

        // Sembrar un user SUSPENDED con el mismo DNI. WBS-110 lo detecta y
        // tira USER_SUSPENDED (409), que no es error de infra: es error de
        // negocio. Rollback de la tx debe restaurar el minor.
        UUID suspendedId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, document_number, status)
                VALUES (?, ?, 'h', 'Susp', ?, 'SUSPENDED')
                """, suspendedId, "susp-" + suspendedId + "@t.com", dni);

        HttpResponse<String> resp = post(
                "/api/v1/family/lots/" + lotA + "/minors/" + id + "/graduate", tokenOwnerA(),
                """
                {"email":"rollback@dev.local","canInvite":true}
                """);
        assertThat(resp.statusCode()).isEqualTo(409);
        assertThat(resp.body()).contains("USER_SUSPENDED");
        Integer stillThere = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot_minors WHERE id = ?::uuid", Integer.class, id);
        assertThat(stillThere).as("menor debe seguir en el padron tras rollback").isEqualTo(1);
    }
}
