package com.securepass.modules.access.infrastructure.rest;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.domain.Role;
import com.securepass.shared.security.JwtService;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #182: un evento del outbox se atribuye al guardia que estaba en la garita
 * cuando se capturo (Event.guardId), no al que sincroniza despues del cambio
 * de turno. Solo si ese guardId es GUARD de este barrio: si no, un cliente
 * podria cargarle ingresos a cualquiera, y se usa el de la sesion.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SyncGuardAttributionHttpTest {

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

    @Test
    void elEventoQuedaAtribuidoAlGuardiaQueLoCapturoSoloSiEsGuardiaDelBarrio() throws Exception {
        UUID barrio = UUID.randomUUID();
        UUID otroBarrio = UUID.randomUUID();
        UUID gate = UUID.randomUUID();
        UUID guardiaA = UUID.randomUUID();
        UUID guardiaB = UUID.randomUUID();
        UUID guardiaAjeno = UUID.randomUUID();
        UUID residente = UUID.randomUUID();
        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'B', ?)", barrio, "b-" + barrio);
        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'O', ?)", otroBarrio, "o-" + otroBarrio);
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, 'G', 'BOTH')", gate, barrio);
        for (UUID u : List.of(guardiaA, guardiaB, guardiaAjeno, residente)) {
            jdbc.update("INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, 'h', 'U', 'ACTIVE')",
                    u, u + "@t.com");
        }
        membership(guardiaA, barrio, "GUARD");
        membership(guardiaB, barrio, "GUARD");
        membership(guardiaAjeno, otroBarrio, "GUARD");
        membership(residente, barrio, "RESIDENT_OWNER");

        UUID deA = UUID.randomUUID();
        UUID deResidente = UUID.randomUUID();
        UUID deAjeno = UUID.randomUUID();
        UUID sinGuardId = UUID.randomUUID();
        String body = "{\"deviceId\":\"" + UUID.randomUUID() + "\",\"events\":["
                + event(deA, gate, guardiaA) + "," + event(deResidente, gate, residente) + ","
                + event(deAjeno, gate, guardiaAjeno) + "," + event(sinGuardId, gate, null) + "]}";
        String tokenB = jwt.issue(guardiaB, barrio, List.of(Role.GUARD), List.of()).token();
        HttpResponse<String> res = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/access/sync"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + tokenB)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);

        assertThat(guardOf(deA)).isEqualTo(guardiaA);
        assertThat(guardOf(deResidente)).isEqualTo(guardiaB);
        assertThat(guardOf(deAjeno)).isEqualTo(guardiaB);
        assertThat(guardOf(sinGuardId)).isEqualTo(guardiaB);
    }

    private void membership(UUID user, UUID barrio, String role) {
        jdbc.update("INSERT INTO memberships (user_id, neighborhood_id, role, active) VALUES (?, ?, ?, TRUE)",
                user, barrio, role);
    }

    private UUID guardOf(UUID clientEventId) {
        return jdbc.queryForObject("SELECT guard_id FROM access_logs WHERE client_event_id = ?", UUID.class,
                clientEventId);
    }

    private static String event(UUID clientEventId, UUID gate, UUID guardId) {
        return "{\"clientEventId\":\"" + clientEventId + "\",\"token\":\"SP1.x.y\",\"gateId\":\"" + gate
                + "\",\"direction\":\"IN\",\"companions\":0,\"capturedAt\":\"" + Instant.now()
                + "\",\"localDecision\":\"DENIED\",\"localDenialReason\":\"NOT_ENROLLED\""
                + (guardId == null ? "" : ",\"guardId\":\"" + guardId + "\"") + "}";
    }
}
