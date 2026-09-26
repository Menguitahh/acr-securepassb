package com.securepass.modules.access.infrastructure.rest;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.access.application.ExportAccessLogsUseCase;
import com.securepass.modules.identity.domain.Role;
import com.securepass.shared.security.CurrentUser;
import com.securepass.shared.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WBS-52 / US-32: GET /api/v1/admin/reports/access-logs.csv. CSV por rango,
 * aislado por barrio, y cada export queda auditado en access_log_exports.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessLogsExportHttpTest {

    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-10T00:00:00Z");

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired ExportAccessLogsUseCase exports;
    @Value("${securepass.cors.allowed-origins:https://admin.securepass.app}") String allowedOrigins;
    String allowedOrigin;

    UUID barrio;
    UUID otroBarrio;
    UUID gate;
    UUID otraGate;
    UUID lot;
    UUID adminId;

    @BeforeEach
    void seed() {
        barrio = UUID.randomUUID();
        otroBarrio = UUID.randomUUID();
        gate = UUID.randomUUID();
        otraGate = UUID.randomUUID();
        lot = UUID.randomUUID();
        adminId = UUID.randomUUID();
        allowedOrigin = allowedOrigins.split(",")[0].trim();

        for (UUID n : List.of(barrio, otroBarrio)) {
            jdbc.update("INSERT INTO neighborhoods (id, name, slug, timezone) VALUES (?, 'B', ?, 'America/Argentina/Mendoza')",
                    n, "b-" + UUID.randomUUID());
        }
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, 'G', 'BOTH')", gate, barrio);
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, 'G', 'BOTH')", otraGate, otroBarrio);
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'L-12')", lot, barrio);
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, 'h', 'admin', 'ACTIVE')
                """, adminId, "a-" + adminId + "@t.com");

        insertLog(barrio, gate, lot, "ALLOWED", null, "Muñoz, \"Tito\"", "2026-09-02T10:00:00Z");
        insertLog(barrio, gate, lot, "DENIED", "EXPIRED", "=HYPERLINK(\"x\")", "2026-09-03T10:00:00Z");
        insertLog(barrio, gate, null, "ALLOWED", null, "fuera de rango", "2026-09-11T10:00:00Z");
        insertLog(otroBarrio, otraGate, null, "ALLOWED", null, "otro barrio", "2026-09-02T10:00:00Z");
    }

    private void insertLog(UUID nid, UUID gateId, UUID lotId, String result, String reason, String name, String at) {
        jdbc.update("""
                INSERT INTO access_logs (
                    id, neighborhood_id, gate_id, lot_id, direction, subject_type, result, denial_reason,
                    subject_name, subject_document, companions, captured_at, client_event_id
                ) VALUES (?, ?, ?, ?, 'IN', 'GUEST', ?, ?, ?, '30111222', 1, ?, ?)
                """, UUID.randomUUID(), nid, gateId, lotId, result, reason, name,
                Timestamp.from(Instant.parse(at)), UUID.randomUUID());
    }

    private HttpResponse<String> export(String query, Role role) throws Exception {
        String token = jwt.issue(adminId, barrio, List.of(role), List.of(lot)).token();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/admin/reports/access-logs.csv?" + query))
                .header("Authorization", "Bearer " + token)
                .header("Origin", allowedOrigin)
                .GET().build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }

    private String range() {
        return "from=" + FROM + "&to=" + TO;
    }

    private int exportsAudited() {
        return jdbc.queryForObject("SELECT count(*) FROM access_log_exports WHERE actor_user_id = ?", Integer.class, adminId);
    }

    @Test
    void exportaElRangoDelBarrioComoCsvYAudita() throws Exception {
        HttpResponse<String> resp = export(range(), Role.ADMIN);

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Type")).hasValue("text/csv;charset=UTF-8");
        // #192: 2026-09-01T00:00Z es 31/08 21:00 en Mendoza.
        assertThat(resp.headers().firstValue("Content-Disposition"))
                .hasValue("attachment; filename=\"access-logs-2026-08-31-2026-09-09.csv\"");
        assertThat(resp.headers().firstValue("Access-Control-Expose-Headers").orElse(""))
                .contains("Content-Disposition");
        assertThat(resp.body().split("\r\n")).containsExactly(
                "\uFEFFcaptured_at,lot_code,direction,subject_type,result,denial_reason,subject_name,subject_document,companions",
                "2026-09-02T07:00:00-03:00,L-12,IN,GUEST,ALLOWED,,\"Muñoz, \"\"Tito\"\"\",30111222,1",
                "2026-09-03T07:00:00-03:00,L-12,IN,GUEST,DENIED,EXPIRED,\"'=HYPERLINK(\"\"x\"\")\",30111222,1");

        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT neighborhood_id, range_from, range_to, lot_id, result FROM access_log_exports WHERE actor_user_id = ?",
                adminId);
        assertThat(audit.get("neighborhood_id")).isEqualTo(barrio);
        assertThat(((Timestamp) audit.get("range_from")).toInstant()).isEqualTo(FROM);
        assertThat(((Timestamp) audit.get("range_to")).toInstant()).isEqualTo(TO);
        assertThat(audit.get("lot_id")).isNull();
        assertThat(audit.get("result")).isNull();
    }

    @Test
    void filtrosDeLoteYResultadoSeAplicanYSeAuditan() throws Exception {
        HttpResponse<String> resp = export(range() + "&lotId=" + lot + "&result=DENIED", Role.ADMIN);

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body().split("\r\n")).hasSize(2);
        assertThat(resp.body()).contains("EXPIRED").doesNotContain("Tito");
        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT lot_id, result FROM access_log_exports WHERE actor_user_id = ?", adminId);
        assertThat(audit.get("lot_id")).isEqualTo(lot);
        assertThat(audit.get("result")).isEqualTo("DENIED");
    }

    @Test
    void rangoMayorANoventaDiasEs422YNoAudita() throws Exception {
        HttpResponse<String> resp = export("from=2026-01-01T00:00:00Z&to=" + TO, Role.ADMIN);

        assertThat(resp.statusCode()).isEqualTo(422);
        assertThat(exportsAudited()).isZero();
    }

    @Test
    void sinRangoEs400() throws Exception {
        assertThat(export("", Role.ADMIN).statusCode()).isEqualTo(400);
        assertThat(exportsAudited()).isZero();
    }

    @Test
    void residenteEs403() throws Exception {
        assertThat(export(range(), Role.RESIDENT_OWNER).statusCode()).isEqualTo(403);
        assertThat(exportsAudited()).isZero();
    }

    @Test
    void laAuditoriaEsAppendOnly() throws Exception {
        export(range(), Role.ADMIN);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM access_log_exports WHERE actor_user_id = ?", adminId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("access_log_exports es append-only");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE access_log_exports SET range_to = now() WHERE actor_user_id = ?", adminId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("access_log_exports es append-only");
    }

    // #189: con los slots ocupados, 429 con Retry-After y sin fila de auditoria.
    @Test
    void conLosSlotsOcupadosEs429() throws Exception {
        CurrentUser otroAdmin = new CurrentUser(adminId, barrio, List.of(Role.ADMIN), List.of());
        try (var a = exports.start(otroAdmin, FROM, TO, null, null);
             var b = exports.start(otroAdmin, FROM, TO, null, null)) {
            int auditedBefore = exportsAudited();

            HttpResponse<String> resp = export(range(), Role.ADMIN);

            assertThat(resp.statusCode()).isEqualTo(429);
            assertThat(resp.headers().firstValue("Retry-After")).hasValue("30");
            assertThat(exportsAudited()).isEqualTo(auditedBefore);
        }
        assertThat(export(range(), Role.ADMIN).statusCode()).isEqualTo(200);
    }

    // #197: el front manda [from, to) con to a la medianoche del dia siguiente
    // al ultimo elegido. El nombre lleva el ultimo dia incluido y el evento en
    // exactamente to no sale.
    @Test
    void finExclusivoANochePoneElUltimoDiaElegidoYNoIncluyeElBorde() throws Exception {
        insertLog(barrio, gate, lot, "ALLOWED", null, "ultimo", "2026-09-24T02:59:59.999999Z");
        insertLog(barrio, gate, lot, "ALLOWED", null, "en el borde", "2026-09-24T03:00:00Z");

        HttpResponse<String> resp = export("from=2026-09-17T03:00:00Z&to=2026-09-24T03:00:00Z", Role.ADMIN);

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Disposition"))
                .hasValue("attachment; filename=\"access-logs-2026-09-17-2026-09-23.csv\"");
        assertThat(resp.body()).contains(",ultimo,").doesNotContain("en el borde");
    }

    // #192: el offset se calcula por fila. Mendoza salio del horario de verano
    // el 2008-03-16 (-02:00 -> -03:00); un archivo que cruza el cambio lleva los dos.
    @Test
    void offsetPorFilaCuandoElRangoCruzaUnCambioDeHorario() throws Exception {
        insertLog(barrio, gate, lot, "ALLOWED", null, "antes", "2008-03-10T15:00:00Z");
        insertLog(barrio, gate, lot, "ALLOWED", null, "despues", "2008-03-20T15:00:00Z");

        HttpResponse<String> resp = export("from=2008-03-01T00:00:00Z&to=2008-03-31T00:00:00Z", Role.ADMIN);

        assertThat(resp.statusCode()).isEqualTo(200);
        String[] lines = resp.body().split("\r\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[1]).startsWith("2008-03-10T13:00:00-02:00,");
        assertThat(lines[2]).startsWith("2008-03-20T12:00:00-03:00,");
    }
}
