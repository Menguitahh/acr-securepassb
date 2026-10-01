package com.securepass.modules.access.infrastructure.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.invitation.application.QrTokenIssuer;
import com.securepass.modules.invitation.application.SigningKeyIssuer;
import com.securepass.modules.invitation.domain.InvitationType;
import com.securepass.shared.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El sync no puede inventar ingresos. Un rechazo de la garita es un hecho
 * fisico: el server no lo sube a ALLOWED aunque hoy el permiso lo deje pasar.
 * Y un evento de acceso sin DNI de alguien no enrolado no es ALLOWED.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class SyncLocalDecisionHttpTest {

    // Jueves 10:00 en Mendoza, dentro de la franja de L-1.
    private static final Instant CAPTURED_AT = Instant.parse("2026-11-05T13:00:00Z");

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
    @Autowired ObjectMapper json;
    @Autowired SigningKeyIssuer signingKeys;
    @Autowired QrTokenIssuer qrTokenIssuer;

    final HttpClient http = HttpClient.newHttpClient();
    UUID barrio;
    UUID gate;
    UUID guard;
    UUID lot;
    UUID worker;
    UUID invitation;
    String workerDni;
    String guestToken;

    @BeforeEach
    void seed() {
        barrio = UUID.randomUUID();
        gate = UUID.randomUUID();
        guard = UUID.randomUUID();
        lot = UUID.randomUUID();
        worker = UUID.randomUUID();
        invitation = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID jti = UUID.randomUUID();
        workerDni = String.valueOf(40_000_000 + ThreadLocalRandom.current().nextInt(9_000_000));
        String guestDni = String.valueOf(30_000_000 + ThreadLocalRandom.current().nextInt(9_000_000));

        jdbc.update("INSERT INTO neighborhoods (id, name, slug, timezone) VALUES (?, 'B', ?, 'America/Argentina/Mendoza')",
                barrio, "b-" + barrio);
        signingKeys.issueForNeighborhood(barrio);
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, 'G', 'BOTH')", gate, barrio);
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, 'h', 'Guardia', 'ACTIVE')",
                guard, "g-" + guard + "@t.com");
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, 'h', 'Titular', 'ACTIVE')",
                owner, "o-" + owner + "@t.com");
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, document_number, status)
                VALUES (?, ?, 'h', 'Trabajador', ?, 'ACTIVE')
                """, worker, "w-" + worker + "@t.com", workerDni);
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'L-1')", lot, barrio);
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'WORKER', jsonb_build_object(
                    'service', 'Jardineria', 'weekdays', '[1,2,3,4,5]'::jsonb,
                    'dailyFrom', '00:00', 'dailyUntil', '23:59'), TRUE)
                """, worker, lot);

        Instant validFrom = CAPTURED_AT.minusSeconds(3600);
        Instant validUntil = CAPTURED_AT.plusSeconds(3600);
        jdbc.update("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status,
                    guest_name, guest_document, companions, valid_from, valid_until,
                    max_uses, used_count, token_kid, jti
                ) VALUES (?, ?, ?, ?, 'SINGLE', 'ACTIVE', 'Invitado', ?, 0, ?, ?, 1, 0,
                          (SELECT kid FROM signing_keys WHERE neighborhood_id=? AND active=TRUE), ?)
                """, invitation, barrio, lot, owner, guestDni, Timestamp.from(validFrom),
                Timestamp.from(validUntil), barrio, jti);
        guestToken = qrTokenIssuer.issue(new QrTokenIssuer.IssueCommand(
                barrio, lot, invitation, jti, InvitationType.SINGLE, validFrom, validUntil, 1));
        jdbc.update("INSERT INTO guest_enrollments (id, neighborhood_id, document_number) VALUES (?, ?, ?)",
                UUID.randomUUID(), barrio, guestDni);
    }

    private void enrolarTrabajador() {
        jdbc.update("INSERT INTO guest_enrollments (id, neighborhood_id, document_number) VALUES (?, ?, ?)",
                UUID.randomUUID(), barrio, workerDni);
    }

    private String workerToken() throws Exception {
        String workerJwt = jwt.issue(worker, barrio, List.of(), List.of()).token();
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/me/worker-qr"))
                .header("Authorization", "Bearer " + workerJwt)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return json.readTree(resp.body()).get("token").asText();
    }

    private String event(UUID clientEventId, String token, Instant capturedAt, String local, String dni) {
        return "{\"clientEventId\":\"" + clientEventId + "\",\"token\":\"" + token + "\","
                + "\"gateId\":\"" + gate + "\",\"direction\":\"IN\",\"companions\":0,"
                + "\"capturedAt\":\"" + capturedAt + "\"" + local
                + (dni == null ? "" : ",\"documentNumber\":\"" + dni + "\"") + "}";
    }

    private static final String ALLOWED = ",\"localDecision\":\"ALLOWED\"";

    private static String denied(String reason) {
        return ",\"localDecision\":\"DENIED\",\"localDenialReason\":\"" + reason + "\"";
    }

    private HttpResponse<String> post(String... events) throws Exception {
        String guardJwt = jwt.issue(guard, barrio, List.of(Role.GUARD), List.of()).token();
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/access/sync"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + guardJwt)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"deviceId\":\"" + UUID.randomUUID() + "\",\"events\":[" + String.join(",", events) + "]}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode sync(String... events) throws Exception {
        HttpResponse<String> resp = post(events);
        assertThat(resp.statusCode()).as("sync: %s", resp.body()).isEqualTo(200);
        return json.readTree(resp.body()).get("results");
    }

    private int logs() {
        return jdbc.queryForObject("SELECT count(*) FROM access_logs WHERE neighborhood_id = ?", Integer.class, barrio);
    }

    private List<String> results(UUID clientEventId) {
        return jdbc.queryForList("SELECT result FROM access_logs WHERE client_event_id = ?", String.class, clientEventId);
    }

    // Caso real: la garita rechazo por franja; despues se amplio la franja y
    // al sincronizar el server re-evaluo con el permiso nuevo.
    @Test
    void unDeniedLocalDeTrabajadorNoSeConvierteEnAllowed() throws Exception {
        enrolarTrabajador();
        UUID ev = UUID.randomUUID();

        JsonNode result = sync(event(ev, workerToken(), CAPTURED_AT, denied("OUT_OF_SCHEDULE"), null)).get(0);

        assertThat(result.get("result").asText()).as(result.toString()).isEqualTo("DENIED");
        assertThat(result.get("reason").asText()).isEqualTo("OUT_OF_SCHEDULE");
        assertThat(results(ev)).containsExactly("DENIED");
        assertThat(jdbc.queryForObject("SELECT denial_reason FROM access_logs WHERE client_event_id = ?",
                String.class, ev)).isEqualTo("OUT_OF_SCHEDULE");
    }

    // Mismo hueco en invitados, con mas dano: consume el pase, abre una
    // estadia falsa y le avisa al residente que su invitado entro.
    @Test
    void unDeniedLocalDeInvitadoNoConsumeNiAbreEstadia() throws Exception {
        UUID ev = UUID.randomUUID();

        JsonNode result = sync(event(ev, guestToken, CAPTURED_AT, denied("EXPIRED"), null)).get(0);

        assertThat(result.get("result").asText()).as(result.toString()).isEqualTo("DENIED");
        assertThat(result.get("reason").asText()).isEqualTo("EXPIRED");
        assertThat(results(ev)).containsExactly("DENIED");
        assertThat(jdbc.queryForObject("SELECT used_count FROM invitations WHERE id = ?", Integer.class, invitation))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stays WHERE invitation_id = ?", Integer.class, invitation))
                .isZero();
    }

    // Caso real: enroll offline con DNI equivocado (el mobile lo cachea como
    // enrolado sin poder verificarlo) y 13 s despues un scan sin DNI que la
    // garita dejo pasar. El server rechaza el enroll pero acepta el acceso.
    @Test
    void trasUnDocumentMismatchElAccesoSinDniNoEsAllowed() throws Exception {
        String token = workerToken();
        UUID enroll = UUID.randomUUID();
        UUID acceso = UUID.randomUUID();

        JsonNode results = sync(
                event(enroll, token, CAPTURED_AT, ALLOWED, "11222333"),
                event(acceso, token, CAPTURED_AT.plusSeconds(13), ALLOWED, null));

        assertThat(results.get(0).get("reason").asText()).isEqualTo("DOCUMENT_MISMATCH");
        assertThat(results.get(1).get("result").asText()).as(results.get(1).toString()).isEqualTo("DENIED");
        assertThat(results.get(1).get("reason").asText()).isEqualTo("NOT_ENROLLED");
        assertThat(results(acceso)).containsExactly("DENIED");
    }

    // El caso seguro sigue auditado: local ALLOWED de un invitado no enrolado.
    @Test
    void invitadoNoEnroladoEntroOfflineYSeAuditaSinConsumirNiAbrirEstadia() throws Exception {
        jdbc.update("DELETE FROM guest_enrollments WHERE neighborhood_id = ?", barrio);
        UUID ev = UUID.randomUUID();

        JsonNode result = sync(event(ev, guestToken, CAPTURED_AT, ALLOWED, null)).get(0);

        assertThat(result.get("result").asText()).as(result.toString()).isEqualTo("DENIED");
        assertThat(result.get("reason").asText()).isEqualTo("NOT_ENROLLED");
        assertThat(jdbc.queryForObject("SELECT used_count FROM invitations WHERE id = ?", Integer.class, invitation))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stays WHERE invitation_id = ?", Integer.class, invitation))
                .isZero();
    }

    @Test
    void trabajadorEnroladoConAllowedLocalSigueEntrando() throws Exception {
        enrolarTrabajador();

        JsonNode result = sync(event(UUID.randomUUID(), workerToken(), CAPTURED_AT, ALLOWED, null)).get(0);

        assertThat(result.get("result").asText()).as(result.toString()).isEqualTo("ALLOWED");
    }

    // Guardia cancela el paso del DNI: la garita no dejo pasar y no hay documento.
    @Test
    void dniCanceladoQuedaNotEnrolledSinEnrolar() throws Exception {
        UUID ev = UUID.randomUUID();

        JsonNode result = sync(event(ev, workerToken(), CAPTURED_AT, denied("NOT_ENROLLED"), null)).get(0);

        assertThat(result.get("result").asText()).as(result.toString()).isEqualTo("DENIED");
        assertThat(result.get("reason").asText()).isEqualTo("NOT_ENROLLED");
        assertThat(results(ev)).containsExactly("DENIED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guest_enrollments WHERE neighborhood_id = ? AND document_number = ?",
                Integer.class, barrio, workerDni)).isZero();
    }

    // Motivos que solo produce la garita offline: senal de dispositivo
    // desincronizado, no se aplanan a UNKNOWN.
    @Test
    void motivosDeGaritaDesincronizadaSeGuardanTalCual() throws Exception {
        UUID sinTrabajador = UUID.randomUUID();
        UUID sinTz = UUID.randomUUID();

        JsonNode results = sync(
                event(sinTrabajador, workerToken(), CAPTURED_AT, denied("WORKER_NOT_SYNCED"), null),
                event(sinTz, guestToken, CAPTURED_AT.plusSeconds(1), denied("NEIGHBORHOOD_TZ_MISSING"), null));

        assertThat(results.get(0).get("reason").asText()).isEqualTo("WORKER_NOT_SYNCED");
        assertThat(results.get(1).get("reason").asText()).isEqualTo("NEIGHBORHOOD_TZ_MISSING");
        assertThat(jdbc.queryForObject("SELECT denial_reason FROM access_logs WHERE client_event_id = ?",
                String.class, sinTrabajador)).isEqualTo("WORKER_NOT_SYNCED");
        assertThat(jdbc.queryForObject("SELECT denial_reason FROM access_logs WHERE client_event_id = ?",
                String.class, sinTz)).isEqualTo("NEIGHBORHOOD_TZ_MISSING");
    }

    // #217: un evento mal armado sale REJECTED sin trabar el outbox; el resto
    // del batch sigue.
    @Test
    void decisionLocalMalArmadaSaleRejectedYElRestoSigue(CapturedOutput output) throws Exception {
        List<String> malArmados = List.of(
                "",
                ",\"localDecision\":\"DENIED\"",
                ALLOWED + ",\"localDenialReason\":\"EXPIRED\"",
                ",\"localDecision\":\"MAYBE\"",
                denied("NO_EXISTE"));
        List<UUID> ids = new ArrayList<>();
        List<String> events = new ArrayList<>();
        for (int i = 0; i < malArmados.size(); i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            events.add(event(id, guestToken, CAPTURED_AT.plusSeconds(i), malArmados.get(i), null));
        }
        UUID bueno = UUID.randomUUID();
        events.add(event(bueno, guestToken, CAPTURED_AT.plusSeconds(60), ALLOWED, null));

        JsonNode results = sync(events.toArray(String[]::new));

        for (int i = 0; i < ids.size(); i++) {
            JsonNode r = results.get(i);
            assertThat(r.get("clientEventId").asText()).isEqualTo(ids.get(i).toString());
            assertThat(r.get("result").asText()).as("%s -> %s", malArmados.get(i), r).isEqualTo("REJECTED");
            assertThat(r.get("rejection").asText()).isEqualTo("INVALID_LOCAL_DECISION");
            assertThat(results(ids.get(i))).isEmpty();
            assertThat(output.getOut()).contains(ids.get(i).toString());
        }
        assertThat(results.get(ids.size()).get("result").asText()).isEqualTo("ALLOWED");
        assertThat(output.getOut()).contains("WARN").contains(guard.toString()).contains("INVALID_LOCAL_DECISION");
    }
}
