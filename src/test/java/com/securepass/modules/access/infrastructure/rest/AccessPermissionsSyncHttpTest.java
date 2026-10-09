package com.securepass.modules.access.infrastructure.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.domain.Role;
import com.securepass.shared.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #180: el sync de permisos lleva los trabajadores ya enrolados, para que la
 * garita sin red pueda exigir el DNI la primera vez (US-66). El criterio es el
 * mismo que usa validate online: enrolamiento del barrio con el DNI del user.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessPermissionsSyncHttpTest {

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    UUID barrio;
    UUID otroBarrio;
    UUID lot;
    UUID otroLot;
    UUID owner;

    @BeforeEach
    void seed() {
        barrio = UUID.randomUUID();
        otroBarrio = UUID.randomUUID();
        lot = UUID.randomUUID();
        otroLot = UUID.randomUUID();
        owner = user(null);
        for (UUID n : List.of(barrio, otroBarrio)) {
            jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'B', ?)", n, "b-" + UUID.randomUUID());
        }
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'L-1')", lot, barrio);
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'L-1')", otroLot, otroBarrio);
    }

    private UUID user(String dni) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, document_number, status)
                VALUES (?, ?, 'h', 'U', ?, 'ACTIVE')
                """, id, "u-" + id + "@t.com", dni);
        return id;
    }

    private static String dni() {
        return Integer.toString(20_000_000 + (int) (Math.random() * 70_000_000));
    }

    /** Trabajador con permiso activo y token vigente en el lote. */
    private UUID worker(UUID nid, UUID lotId, String dni) {
        UUID id = user(dni);
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'WORKER', jsonb_build_object(
                    'service', 'Jardineria', 'weekdays', '[1,2,3,4,5]'::jsonb,
                    'dailyFrom', '08:00', 'dailyUntil', '18:00'), TRUE)
                """, id, lotId);
        jdbc.update("INSERT INTO worker_tokens (user_id, neighborhood_id, jti) VALUES (?, ?, ?)",
                id, nid, UUID.randomUUID());
        return id;
    }

    private void enroll(UUID nid, String dni, UUID invitationId) {
        jdbc.update("""
                INSERT INTO guest_enrollments (neighborhood_id, invitation_id, document_number)
                VALUES (?, ?, ?)
                """, nid, invitationId, dni);
    }

    private JsonNode sync(String query) throws Exception {
        String token = jwt.issue(UUID.randomUUID(), barrio, List.of(Role.GUARD), List.of()).token();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/access/permissions/sync" + query))
                .header("Authorization", "Bearer " + token)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return json.readTree(resp.body());
    }

    private static List<String> ids(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    @Test
    void separaEnroladosDeNoEnrolados() throws Exception {
        String dniEnrolado = dni();
        UUID enrolado = worker(barrio, lot, dniEnrolado);
        enroll(barrio, dniEnrolado, null);
        UUID nuevo = worker(barrio, lot, dni());

        JsonNode body = sync("");

        assertThat(body.get("permissions")).hasSize(2);
        assertThat(ids(body.get("enrolledUserIds")))
                .containsExactly(enrolado.toString())
                .doesNotContain(nuevo.toString());
    }

    // Mismo criterio que validate online: el enrolamiento es por persona, asi
    // que el DNI enrolado antes como invitado tambien cuenta. Con
    // invitation_id IS NULL la garita offline lo rechazaria y la online no.
    @Test
    void cuentaElEnrolamientoHechoComoInvitado() throws Exception {
        String dniComun = dni();
        UUID worker = worker(barrio, lot, dniComun);
        UUID inv = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status,
                    guest_name, guest_document, companions,
                    valid_from, valid_until, max_uses, used_count, token_kid, jti
                ) VALUES (?, ?, ?, ?, 'SINGLE', 'USED', 'U', ?, 0, now() - interval '2 days',
                          now() - interval '1 day', 1, 1, 'kid-1', ?)
                """, inv, barrio, lot, owner, dniComun, UUID.randomUUID());
        enroll(barrio, dniComun, inv);

        assertThat(ids(sync("").get("enrolledUserIds"))).containsExactly(worker.toString());
    }

    @Test
    void elEnrolamientoDeOtroBarrioNoCuenta() throws Exception {
        String dniComun = dni();
        worker(barrio, lot, dniComun);
        enroll(otroBarrio, dniComun, null);

        assertThat(sync("").get("enrolledUserIds")).isEmpty();
    }

    // D-13: el sync no expone mas personas que las que ya viajan en permissions.
    @Test
    void soloTrabajadoresDelSync() throws Exception {
        String sinPermiso = dni();
        user(sinPermiso);
        enroll(barrio, sinPermiso, null);
        String deOtroBarrio = dni();
        worker(otroBarrio, otroLot, deOtroBarrio);
        enroll(barrio, deOtroBarrio, null);
        String revocado = dni();
        UUID r = worker(barrio, lot, revocado);
        enroll(barrio, revocado, null);
        jdbc.update("UPDATE worker_tokens SET revoked_at = now() WHERE user_id = ?", r);

        assertThat(sync("").get("enrolledUserIds")).isEmpty();
    }

    // since pagina permissions; los enrolados salen completos siempre, para
    // que el cache local se reemplace entero en cada sync.
    @Test
    void sinceNoRecortaLosEnrolados() throws Exception {
        String dniEnrolado = dni();
        UUID enrolado = worker(barrio, lot, dniEnrolado);
        enroll(barrio, dniEnrolado, null);

        JsonNode body = sync("?since=2999-01-01T00:00:00Z");

        assertThat(body.get("permissions")).isEmpty();
        assertThat(ids(body.get("enrolledUserIds"))).containsExactly(enrolado.toString());
    }

    // securepassf#179: el scanner reemplaza su cache de invitados enrolados
    // con esta lista, como hace con los trabajadores.
    @Test
    void listaLasInvitacionesActivasCuyoDniYaEstaEnrolado() throws Exception {
        String dniEnrolado = dni();
        UUID previa = invitation(barrio, lot, "USED", dniEnrolado);
        enroll(barrio, dniEnrolado, previa);
        UUID activaEnrolada = invitation(barrio, lot, "ACTIVE", dniEnrolado);
        UUID activaSinEnrolar = invitation(barrio, lot, "ACTIVE", dni());
        String dniAjeno = dni();
        UUID deOtroBarrio = invitation(otroBarrio, otroLot, "ACTIVE", dniAjeno);
        enroll(otroBarrio, dniAjeno, deOtroBarrio);

        List<String> enrolled = ids(sync("").get("enrolledInvitationIds"));
        assertThat(enrolled).containsExactly(activaEnrolada.toString());
        assertThat(enrolled).doesNotContain(previa.toString(), activaSinEnrolar.toString(), deOtroBarrio.toString());
    }

    private UUID invitation(UUID nid, UUID lotId, String status, String dni) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status,
                    guest_name, guest_document, companions,
                    valid_from, valid_until, max_uses, used_count, token_kid, jti
                ) VALUES (?, ?, ?, ?, 'SINGLE', ?, 'U', ?, 0, now() - interval '1 hour',
                          now() + interval '1 day', 1, 0, 'kid-1', ?)
                """, id, nid, lotId, owner, status, dni, UUID.randomUUID());
        return id;
    }
}
