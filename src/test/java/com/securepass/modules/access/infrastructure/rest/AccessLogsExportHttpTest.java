package com.securepass.modules.access.infrastructure.rest;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.domain.Role;
import com.securepass.shared.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

        for (UUID n : List.of(barrio, otroBarrio)) {
            jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'B', ?)", n, "b-" + UUID.randomUUID());
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
        assertThat(resp.headers().firstValue("Content-Disposition"))
                .hasValue("attachment; filename=\"access-logs-2026-09-01-2026-09-10.csv\"");
        assertThat(resp.body().split("\r\n")).containsExactly(
                "﻿captured_at,lot_code,direction,subject_type,result,denial_reason,subject_name,subject_document,companions",
                "2026-09-02T10:00:00Z,L-12,IN,GUEST,ALLOWED,,\"Muñoz, \"\"Tito\"\"\",30111222,1",
                "2026-09-03T10:00:00Z,L-12,IN,GUEST,DENIED,EXPIRED,\"'=HYPERLINK(\"\"x\"\")\",30111222,1");

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
}
