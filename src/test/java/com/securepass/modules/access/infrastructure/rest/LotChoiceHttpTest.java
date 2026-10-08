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
 * #217: el lote que elige el guardia llega a la bitacora. Online, con varios
 * lotes en franja y sin lotId, validate pide elegir y no guarda nada (mismo
 * patron que NEEDS_ENROLLMENT). Un lotId que el backend no habilita es 400
 * online y REJECTED por evento en sync, sin trabar el resto del batch.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class LotChoiceHttpTest {

    // Jueves 10:00 en Mendoza.
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
    UUID todoElDia;
    UUID soloJuevesALaManana;
    UUID fueraDeFranja;
    UUID loteInvitacion;
    UUID invitation;
    UUID worker;
    String guestToken;
    String workerDni;

    @BeforeEach
    void seed() {
        barrio = UUID.randomUUID();
        gate = UUID.randomUUID();
        guard = UUID.randomUUID();
        todoElDia = UUID.randomUUID();
        soloJuevesALaManana = UUID.randomUUID();
        fueraDeFranja = UUID.randomUUID();
        loteInvitacion = UUID.randomUUID();
        invitation = UUID.randomUUID();
        worker = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID jti = UUID.randomUUID();
        String guestDni = String.valueOf(30_000_000 + ThreadLocalRandom.current().nextInt(9_000_000));
        workerDni = String.valueOf(40_000_000 + ThreadLocalRandom.current().nextInt(9_000_000));

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
        String[][] lots = {
                {todoElDia.toString(), "L-1", "[1,2,3,4,5]", "00:00", "23:59"},
                {soloJuevesALaManana.toString(), "L-2", "[4]", "09:00", "11:00"},
                {fueraDeFranja.toString(), "L-3", "[1]", "08:00", "09:00"}};
        for (String[] l : lots) {
            jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?::uuid, ?, ?)", l[0], barrio, l[1]);
            jdbc.update("""
                    INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                    VALUES (?, ?::uuid, 'WORKER', jsonb_build_object(
                        'service', 'Jardineria', 'weekdays', ?::jsonb,
                        'dailyFrom', ?, 'dailyUntil', ?), TRUE)
                    """, worker, l[0], l[2], l[3], l[4]);
        }
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'L-9')", loteInvitacion, barrio);

        Instant validFrom = CAPTURED_AT.minusSeconds(3600);
        Instant validUntil = CAPTURED_AT.plusSeconds(3600);
        jdbc.update("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status,
                    guest_name, guest_document, companions, valid_from, valid_until,
                    max_uses, used_count, token_kid, jti
                ) VALUES (?, ?, ?, ?, 'SINGLE', 'ACTIVE', 'Invitado', ?, 0, ?, ?, 1, 0,
                          (SELECT kid FROM signing_keys WHERE neighborhood_id=? AND active=TRUE), ?)
                """, invitation, barrio, loteInvitacion, owner, guestDni, Timestamp.from(validFrom),
                Timestamp.from(validUntil), barrio, jti);
        guestToken = qrTokenIssuer.issue(new QrTokenIssuer.IssueCommand(
                barrio, loteInvitacion, invitation, jti, InvitationType.SINGLE, validFrom, validUntil, 1));

        for (String dni : List.of(guestDni, workerDni)) {
            jdbc.update("INSERT INTO guest_enrollments (id, neighborhood_id, document_number) VALUES (?, ?, ?)",
                    UUID.randomUUID(), barrio, dni);
        }
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

    private HttpResponse<String> post(String path, String body) throws Exception {
        String guardJwt = jwt.issue(guard, barrio, List.of(Role.GUARD), List.of()).token();
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/access/" + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + guardJwt)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> validate(String token, UUID lotId) throws Exception {
        return post("validate", "{\"gateId\":\"" + gate + "\",\"token\":\"" + token + "\","
                + "\"direction\":\"IN\",\"companions\":0,\"capturedAt\":\"" + CAPTURED_AT + "\","
                + "\"clientEventId\":\"" + UUID.randomUUID() + "\""
                + (lotId == null ? "" : ",\"lotId\":\"" + lotId + "\"") + "}");
    }

    private String event(UUID clientEventId, String token, UUID lotId, Instant capturedAt) {
        return "{\"clientEventId\":\"" + clientEventId + "\",\"token\":\"" + token + "\","
                + "\"gateId\":\"" + gate + "\",\"direction\":\"IN\",\"companions\":0,"
                + "\"capturedAt\":\"" + capturedAt + "\",\"localDecision\":\"ALLOWED\""
                + (lotId == null ? "" : ",\"lotId\":\"" + lotId + "\"") + "}";
    }

    private JsonNode sync(String... events) throws Exception {
        HttpResponse<String> resp = post("sync",
                "{\"deviceId\":\"" + UUID.randomUUID() + "\",\"events\":[" + String.join(",", events) + "]}");
        assertThat(resp.statusCode()).as("sync: %s", resp.body()).isEqualTo(200);
        return json.readTree(resp.body());
    }

    private int logs() {
        return jdbc.queryForObject("SELECT count(*) FROM access_logs WHERE neighborhood_id = ?", Integer.class, barrio);
    }

    private List<String> lotIds(JsonNode lots) {
        List<String> out = new ArrayList<>();
        lots.forEach(l -> out.add(l.get("lotId").asText()));
        return out;
    }

    private static void assertInvalidLot(HttpResponse<String> resp) {
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(400);
        assertThat(resp.body()).contains("INVALID_LOT");
    }

    @Test
    void onlineConVariosLotesSinLotIdPideElegirYNoGuarda() throws Exception {
        HttpResponse<String> resp = validate(workerToken(), null);

        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        JsonNode body = json.readTree(resp.body());
        assertThat(body.get("result").asText()).isEqualTo("NEEDS_LOT_SELECTION");
        assertThat(body.has("logId")).isFalse();
        assertThat(lotIds(body.get("worker").get("lots")))
                .containsExactlyInAnyOrder(todoElDia.toString(), soloJuevesALaManana.toString());
        assertThat(logs()).isZero();
    }

    @Test
    void onlineConLotIdElegidoGuardaEseLote() throws Exception {
        HttpResponse<String> resp = validate(workerToken(), soloJuevesALaManana);

        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        JsonNode body = json.readTree(resp.body());
        assertThat(body.get("result").asText()).isEqualTo("ALLOWED_WORKER");
        assertThat(lotIds(body.get("worker").get("lots"))).containsExactly(soloJuevesALaManana.toString());
        assertThat(jdbc.queryForObject("SELECT lot_id FROM access_logs WHERE id = ?", UUID.class,
                UUID.fromString(body.get("logId").asText()))).isEqualTo(soloJuevesALaManana);
    }

    @Test
    void onlineConLoteDelTrabajadorFueraDeFranjaSeDeniegaYSeAudita() throws Exception {
        // #219: el lote es suyo; que la franja haya cerrado es una decision de
        // acceso, no un error del cliente (p. ej. cerro entre la lista y el reenvio).
        HttpResponse<String> resp = validate(workerToken(), fueraDeFranja);

        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        JsonNode body = json.readTree(resp.body());
        assertThat(body.get("result").asText()).isEqualTo("DENIED");
        assertThat(body.get("reason").asText()).isEqualTo("OUT_OF_SCHEDULE");
        assertThat(logs()).isEqualTo(1);
    }

    @Test
    void onlineConLotIdAjenoEs400YNoGuarda() throws Exception {
        assertInvalidLot(validate(workerToken(), UUID.randomUUID()));
        assertThat(logs()).isZero();
    }

    @Test
    void invitadoConElLoteDeLaInvitacionEntra() throws Exception {
        HttpResponse<String> resp = validate(guestToken, loteInvitacion);

        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        assertThat(json.readTree(resp.body()).get("result").asText()).isEqualTo("ALLOWED");
    }

    @Test
    void invitadoConOtroLoteEs400YNoConsumeElPase() throws Exception {
        assertInvalidLot(validate(guestToken, todoElDia));

        assertThat(jdbc.queryForObject("SELECT used_count FROM invitations WHERE id = ?", Integer.class, invitation))
                .isZero();
        assertThat(logs()).isZero();
    }

    // La franja se evalua en el capturedAt del evento, no al sincronizar: L-2
    // solo abre los jueves de 9 a 11.
    @Test
    void syncDeTrabajadorGuardaElLoteElegidoEvaluadoEnCapturedAt() throws Exception {
        UUID ev = UUID.randomUUID();

        JsonNode result = sync(event(ev, workerToken(), soloJuevesALaManana, CAPTURED_AT)).get("results").get(0);

        assertThat(result.get("result").asText()).isEqualTo("ALLOWED");
        assertThat(jdbc.queryForObject("SELECT lot_id FROM access_logs WHERE client_event_id = ?", UUID.class, ev))
                .isEqualTo(soloJuevesALaManana);
    }

    @Test
    void syncConLotIdInvalidoSaleRejectedDejaRastroYElRestoSigue(CapturedOutput output) throws Exception {
        String token = workerToken();
        UUID malo = UUID.randomUUID();
        UUID bueno = UUID.randomUUID();

        JsonNode results = sync(
                event(malo, token, UUID.randomUUID(), CAPTURED_AT),
                event(bueno, token, todoElDia, CAPTURED_AT.plusSeconds(60))).get("results");

        JsonNode rechazado = results.get(0);
        assertThat(rechazado.get("clientEventId").asText()).isEqualTo(malo.toString());
        assertThat(rechazado.get("result").asText()).isEqualTo("REJECTED");
        assertThat(rechazado.get("rejection").asText()).isEqualTo("INVALID_LOT");
        assertThat(rechazado.path("reason").isNull() || rechazado.path("reason").isMissingNode()).isTrue();
        assertThat(rechazado.path("logId").isNull() || rechazado.path("logId").isMissingNode()).isTrue();
        assertThat(results.get(1).get("result").asText()).isEqualTo("ALLOWED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM access_logs WHERE client_event_id = ?", Integer.class, malo))
                .isZero();
        // El ingreso fisico ocurrio: no se pierde en silencio.
        assertThat(output.getOut()).contains("WARN").contains(malo.toString()).contains(guard.toString())
                .contains("INVALID_LOT");
    }

    @Test
    void syncConLoteDelTrabajadorFueraDeFranjaSaleDenied() throws Exception {
        UUID ev = UUID.randomUUID();

        JsonNode result = sync(event(ev, workerToken(), fueraDeFranja, CAPTURED_AT)).get("results").get(0);

        assertThat(result.get("result").asText()).isEqualTo("DENIED");
        assertThat(result.get("reason").asText()).isEqualTo("OUT_OF_SCHEDULE");
    }

    // ---- #219: primer ingreso del trabajador por enroll ----

    // Domingo 10:00 en Mendoza: ningun lote en franja.
    private static final Instant DOMINGO = Instant.parse("2026-11-08T13:00:00Z");

    private void desenrolar() {
        jdbc.update("DELETE FROM guest_enrollments WHERE neighborhood_id = ? AND document_number = ?", barrio, workerDni);
    }

    private int enrolamientos() {
        return jdbc.queryForObject("SELECT count(*) FROM guest_enrollments WHERE neighborhood_id = ? AND document_number = ?",
                Integer.class, barrio, workerDni);
    }

    private HttpResponse<String> enroll(String token, String dni, UUID lotId, Instant capturedAt, UUID clientEventId)
            throws Exception {
        return post("enroll", "{\"token\":\"" + token + "\",\"documentNumber\":\"" + dni + "\","
                + "\"gateId\":\"" + gate + "\",\"clientEventId\":\"" + clientEventId + "\","
                + "\"capturedAt\":\"" + capturedAt + "\""
                + (lotId == null ? "" : ",\"lotId\":\"" + lotId + "\"") + "}");
    }

    private HttpResponse<String> enroll(UUID lotId, Instant capturedAt) throws Exception {
        return enroll(workerToken(), workerDni, lotId, capturedAt, UUID.randomUUID());
    }

    @Test
    void needsEnrollmentDelTrabajadorTraeLosLotesEnFranja() throws Exception {
        desenrolar();

        HttpResponse<String> resp = validate(workerToken(), null);

        JsonNode body = json.readTree(resp.body());
        assertThat(body.get("result").asText()).isEqualTo("NEEDS_ENROLLMENT");
        assertThat(lotIds(body.get("worker").get("lots")))
                .containsExactlyInAnyOrder(todoElDia.toString(), soloJuevesALaManana.toString());
        assertThat(logs()).isZero();
    }

    @Test
    void enrollConLoteElegidoGuardaEseLoteYEnrola() throws Exception {
        desenrolar();
        UUID ev = UUID.randomUUID();

        HttpResponse<String> resp = enroll(workerToken(), workerDni, soloJuevesALaManana, CAPTURED_AT, ev);

        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        assertThat(json.readTree(resp.body()).get("result").asText()).isEqualTo("ALLOWED");
        assertThat(jdbc.queryForObject("SELECT lot_id FROM access_logs WHERE client_event_id = ?", UUID.class, ev))
                .isEqualTo(soloJuevesALaManana);
        assertThat(enrolamientos()).isEqualTo(1);
    }

    @Test
    void enrollConVariosLotesYSinLotIdEs400YNoEnrola() throws Exception {
        desenrolar();

        assertInvalidLot(enroll(null, CAPTURED_AT));

        assertThat(enrolamientos()).isZero();
        assertThat(logs()).isZero();
    }

    @Test
    void enrollConLoteAjenoEs400YNoEnrola() throws Exception {
        desenrolar();

        assertInvalidLot(enroll(UUID.randomUUID(), CAPTURED_AT));

        assertThat(enrolamientos()).isZero();
        assertThat(logs()).isZero();
    }

    @Test
    void enrollConLoteDelTrabajadorFueraDeFranjaSeDeniegaYNoEnrola() throws Exception {
        desenrolar();

        HttpResponse<String> resp = enroll(fueraDeFranja, CAPTURED_AT);

        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        JsonNode body = json.readTree(resp.body());
        assertThat(body.get("result").asText()).isEqualTo("DENIED");
        assertThat(body.get("reason").asText()).isEqualTo("OUT_OF_SCHEDULE");
        assertThat(enrolamientos()).isZero();
        assertThat(logs()).isEqualTo(1);
    }

    // Antes no se evaluaba la franja: un enroll directo dejaba entrar a
    // cualquier hora con tener un permiso activo.
    @Test
    void enrollSinNingunLoteEnFranjaSeDeniegaYNoEnrola() throws Exception {
        desenrolar();

        HttpResponse<String> resp = enroll(null, DOMINGO);

        JsonNode body = json.readTree(resp.body());
        assertThat(body.get("result").asText()).as(resp.body()).isEqualTo("DENIED");
        assertThat(body.get("reason").asText()).isEqualTo("OUT_OF_SCHEDULE");
        assertThat(enrolamientos()).isZero();
    }

    // El documento antes que el lote: un DOCUMENT_MISMATCH se audita aunque
    // el lote tambien este mal.
    @Test
    void enrollConOtroDniYLoteAjenoAuditaElMismatch() throws Exception {
        desenrolar();

        HttpResponse<String> resp = enroll(workerToken(), "11222333", UUID.randomUUID(), CAPTURED_AT, UUID.randomUUID());

        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        JsonNode body = json.readTree(resp.body());
        assertThat(body.get("result").asText()).isEqualTo("DENIED");
        assertThat(body.get("reason").asText()).isEqualTo("DOCUMENT_MISMATCH");
        assertThat(logs()).isEqualTo(1);
    }

    @Test
    void syncDeEnrollConLoteElegidoGuardaEseLote() throws Exception {
        desenrolar();
        UUID ev = UUID.randomUUID();
        String evento = "{\"clientEventId\":\"" + ev + "\",\"token\":\"" + workerToken() + "\","
                + "\"gateId\":\"" + gate + "\",\"direction\":\"IN\",\"companions\":0,"
                + "\"capturedAt\":\"" + CAPTURED_AT + "\",\"localDecision\":\"ALLOWED\",\"documentNumber\":\"" + workerDni + "\","
                + "\"lotId\":\"" + soloJuevesALaManana + "\"}";

        JsonNode result = sync(evento).get("results").get(0);

        assertThat(result.get("result").asText()).isEqualTo("ENROLLED");
        assertThat(jdbc.queryForObject("SELECT lot_id FROM access_logs WHERE client_event_id = ?", UUID.class, ev))
                .isEqualTo(soloJuevesALaManana);
    }

    @Test
    void syncDeEnrollConLoteAjenoSaleRejected() throws Exception {
        desenrolar();
        UUID ev = UUID.randomUUID();
        String evento = "{\"clientEventId\":\"" + ev + "\",\"token\":\"" + workerToken() + "\","
                + "\"gateId\":\"" + gate + "\",\"direction\":\"IN\",\"companions\":0,"
                + "\"capturedAt\":\"" + CAPTURED_AT + "\",\"localDecision\":\"ALLOWED\",\"documentNumber\":\"" + workerDni + "\","
                + "\"lotId\":\"" + UUID.randomUUID() + "\"}";

        JsonNode result = sync(evento).get("results").get(0);

        assertThat(result.get("result").asText()).isEqualTo("REJECTED");
        assertThat(result.get("rejection").asText()).isEqualTo("INVALID_LOT");
        assertThat(enrolamientos()).isZero();
    }
}
