package com.securepass.modules.access.application;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.access.domain.Direction;
import com.securepass.modules.invitation.application.QrTokenIssuer;
import com.securepass.modules.invitation.application.SigningKeyRepository;
import com.securepass.modules.invitation.domain.InvitationType;
import com.securepass.modules.invitation.domain.SigningKey;
import com.securepass.shared.crypto.AesGcmCipher;
import com.securepass.shared.crypto.Ed25519KeyFactory;
import com.securepass.shared.crypto.Ed25519KeyPair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WBS-62: en enrolamiento diferido, la fila de {@code guest_enrollments}
 * queda vinculada al evento con {@code capturedAt} minimo, aunque el batch
 * llegue en orden inverso. Los ingresos de ambos garitas se persisten con
 * su {@code invitation_id} propio: la regla temporal aplica al enrolamiento
 * (una persona, una fila), no al log de acceso.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SyncAccessEnrollFirstCapturedWinsTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired SyncAccessUseCase useCase;
    @Autowired QrTokenIssuer issuer;
    @Autowired SigningKeyRepository signingKeys;
    @Autowired AesGcmCipher cipher;

    UUID neighborhoodId;
    UUID lotId;
    UUID gateEarly;
    UUID gateLate;
    UUID guardEarly;
    UUID guardLate;
    UUID creatorId;
    String kid;

    @BeforeEach
    void seed() {
        neighborhoodId = UUID.randomUUID();
        lotId = UUID.randomUUID();
        gateEarly = UUID.randomUUID();
        gateLate = UUID.randomUUID();
        guardEarly = UUID.randomUUID();
        guardLate = UUID.randomUUID();
        creatorId = UUID.randomUUID();

        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, ?, ?)",
                neighborhoodId, "Barrio", "b-" + UUID.randomUUID());
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, ?)",
                lotId, neighborhoodId, "L-" + lotId.toString().substring(0, 8));
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, ?, 'BOTH')",
                gateEarly, neighborhoodId, "G-early");
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, ?, 'BOTH')",
                gateLate, neighborhoodId, "G-late");
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, ?, ?)",
                guardEarly, "g-early-" + UUID.randomUUID() + "@t.com", "hash", "Guardia Temprano");
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, ?, ?)",
                guardLate, "g-late-" + UUID.randomUUID() + "@t.com", "hash", "Guardia Tardio");
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, ?, ?)",
                creatorId, "u-" + UUID.randomUUID() + "@t.com", "hash", "Titular");

        Ed25519KeyPair kp = Ed25519KeyFactory.generate();
        kid = "kid-" + UUID.randomUUID().toString().substring(0, 6);
        signingKeys.save(new SigningKey(
                UUID.randomUUID(), neighborhoodId, kid,
                kp.publicKey(), cipher.encrypt(kp.privateKey()),
                true, Instant.now(), null));
    }

    @Test
    void enrolamientoQuedaVinculadoAlGarageDelCapturedAtMinimoAunConBatchInverso() {
        String documento = "30123456";
        UUID invEarly = UUID.randomUUID();
        UUID invLate = UUID.randomUUID();
        String tokenEarly = insertActiveSingle(invEarly, documento);
        String tokenLate = insertActiveSingle(invLate, documento);

        UUID clientEarly = UUID.randomUUID();
        UUID clientLate = UUID.randomUUID();
        Instant tEarly = Instant.now().minusSeconds(300);
        Instant tLate = Instant.now().minusSeconds(60);

        // Orden inverso deliberado: el evento tardio va primero en el array.
        // Si el sort por capturedAt esta ausente, el enrolamiento persistido
        // va a quedar con gate/guard del tardio, y el test falla.
        SyncAccessUseCase.Event late = new SyncAccessUseCase.Event(
                clientLate, tokenLate, gateLate, Direction.IN, 0, tLate, "ALLOWED", null, documento, null);
        SyncAccessUseCase.Event early = new SyncAccessUseCase.Event(
                clientEarly, tokenEarly, gateEarly, Direction.IN, 0, tEarly, "ALLOWED", null, documento, null);

        SyncAccessUseCase.Report report = useCase.sync(
                neighborhoodId, guardLate, List.of(late, early));

        assertThat(report.results()).hasSize(2);
        Map<UUID, SyncAccessUseCase.EventResult> byId = report.results().stream()
                .collect(Collectors.toMap(SyncAccessUseCase.EventResult::clientEventId, Function.identity()));

        SyncAccessUseCase.EventResult earlyRes = byId.get(clientEarly);
        SyncAccessUseCase.EventResult lateRes = byId.get(clientLate);

        // Ambos son ENROLLED (el tardio reusa la fila via saveIfAbsent, no se rechaza).
        assertThat(earlyRes.result()).isEqualTo(SyncAccessUseCase.Outcome.ENROLLED);
        assertThat(lateRes.result()).isEqualTo(SyncAccessUseCase.Outcome.ENROLLED);
        assertThat(earlyRes.enrollmentId()).isEqualTo(lateRes.enrollmentId());

        // Aserto duro: la fila persistida tiene gate/guard del EVENTO MAS TEMPRANO,
        // no del que llego primero en el array.
        Map<String, Object> enrollmentRow = jdbc.queryForMap(
                "SELECT id, invitation_id, enrolled_by, gate_id FROM guest_enrollments WHERE neighborhood_id = ? AND document_number = ?",
                neighborhoodId, documento);
        // El guardia del /sync es guardLate (viene del JWT), no del evento —
        // pero gateId y clientEventId son del evento. Se persiste el gate del temprano.
        assertThat(enrollmentRow.get("gate_id")).isEqualTo(gateEarly);
        assertThat(enrollmentRow.get("invitation_id")).isEqualTo(invEarly);
        assertThat(enrollmentRow.get("id")).isEqualTo(earlyRes.enrollmentId());

        // Ambos ingresos quedan con su invitation_id propio: la regla temporal
        // no borra el ingreso del tardio.
        Map<String, Object> earlyLog = jdbc.queryForMap(
                "SELECT invitation_id, gate_id, result, synced_offline FROM access_logs WHERE client_event_id = ?",
                clientEarly);
        assertThat(earlyLog.get("invitation_id")).isEqualTo(invEarly);
        assertThat(earlyLog.get("gate_id")).isEqualTo(gateEarly);
        assertThat(earlyLog.get("result")).isEqualTo("ALLOWED");
        assertThat(earlyLog.get("synced_offline")).isEqualTo(true);

        Map<String, Object> lateLog = jdbc.queryForMap(
                "SELECT invitation_id, gate_id, result, synced_offline FROM access_logs WHERE client_event_id = ?",
                clientLate);
        assertThat(lateLog.get("invitation_id")).isEqualTo(invLate);
        assertThat(lateLog.get("gate_id")).isEqualTo(gateLate);
        assertThat(lateLog.get("result")).isEqualTo("ALLOWED");
        assertThat(lateLog.get("synced_offline")).isEqualTo(true);

        // Una sola fila en guest_enrollments para (barrio, documento).
        Integer enrollCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM guest_enrollments WHERE neighborhood_id = ? AND document_number = ?",
                Integer.class, neighborhoodId, documento);
        assertThat(enrollCount).isEqualTo(1);
    }

    private String insertActiveSingle(UUID invitationId, String documento) {
        UUID jti = UUID.randomUUID();
        Instant validFrom = Instant.now().minusSeconds(7200);
        Instant validUntil = Instant.now().plusSeconds(7200);
        jdbc.update("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status,
                    guest_name, guest_document, companions, valid_from, valid_until,
                    max_uses, used_count, token_kid, jti
                ) VALUES (?, ?, ?, ?, 'SINGLE', 'ACTIVE', 'Maria', ?, 0, ?, ?, 1, 0, ?, ?)
                """,
                invitationId, neighborhoodId, lotId, creatorId, documento,
                Timestamp.from(validFrom), Timestamp.from(validUntil), kid, jti);
        return issuer.issue(new QrTokenIssuer.IssueCommand(
                neighborhoodId, lotId, invitationId, jti, InvitationType.SINGLE,
                validFrom, validUntil, 1));
    }
}
