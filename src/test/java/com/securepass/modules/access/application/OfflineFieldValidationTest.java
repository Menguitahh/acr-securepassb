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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WBS-56: prueba de campo offline.
 * 200 escaneos capturados sin red, sincronizados en tandas.
 * Se verifica: cero perdidas, cero duplicados, cero falsos permitidos.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OfflineFieldValidationTest {

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
    UUID gateId;
    UUID guardId;
    UUID creatorId;
    String kid;

    @BeforeEach
    void seed() {
        neighborhoodId = UUID.randomUUID();
        lotId = UUID.randomUUID();
        gateId = UUID.randomUUID();
        guardId = UUID.randomUUID();
        creatorId = UUID.randomUUID();

        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, ?, ?)",
                neighborhoodId, "Barrio", "b-" + UUID.randomUUID());
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, ?)",
                lotId, neighborhoodId, "L-" + lotId.toString().substring(0, 8));
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, ?, 'BOTH')",
                gateId, neighborhoodId, "G-" + gateId.toString().substring(0, 8));
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, ?, ?)",
                guardId, "g-" + UUID.randomUUID() + "@t.com", "hash", "Guardia");
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
    void doscientosEscaneosOfflineSincronizadosSinPerdidasNiFalsosPermitidos() {
        Instant base = Instant.now().minusSeconds(3600);

        // 100 invitaciones validas escaneadas una vez -> 100 ALLOWED
        List<String> validSingleScan = new ArrayList<>(100);
        for (int i = 0; i < 100; i++) {
            validSingleScan.add(insertActiveSingle(UUID.randomUUID()));
        }

        // 30 invitaciones validas escaneadas dos veces -> 30 ALLOWED + 30 REPLAY
        List<String> validDoubleScan = new ArrayList<>(30);
        for (int i = 0; i < 30; i++) {
            validDoubleScan.add(insertActiveSingle(UUID.randomUUID()));
        }

        // 20 invitaciones expiradas -> 20 DENIED (EXPIRED)
        List<String> expired = new ArrayList<>(20);
        for (int i = 0; i < 20; i++) {
            expired.add(insertExpiredSingle(UUID.randomUUID()));
        }

        // 20 tokens invalidos -> 20 DENIED (INVALID_SIGNATURE)
        List<String> garbage = new ArrayList<>(20);
        for (int i = 0; i < 20; i++) {
            garbage.add("SP1.garbage-" + i);
        }

        // Armar 200 eventos con clientEventId unico
        List<SyncAccessUseCase.Event> events = new ArrayList<>(200);
        int t = 0;
        for (String tok : validSingleScan) {
            events.add(event(tok, base.plusSeconds(t++)));
        }
        for (String tok : validDoubleScan) {
            events.add(event(tok, base.plusSeconds(t++)));
        }
        for (String tok : validDoubleScan) {
            // segunda captura, mas tarde -> el sync los ordena por capturedAt
            events.add(event(tok, base.plusSeconds(1000 + t++)));
        }
        for (String tok : expired) {
            events.add(event(tok, base.plusSeconds(t++)));
        }
        for (String tok : garbage) {
            events.add(event(tok, base.plusSeconds(t++)));
        }
        assertThat(events).hasSize(200);

        // Mezclar y particionar en 4 tandas de 50 (respeta MAX_BATCH)
        Collections.shuffle(events, new Random(42));
        int accepted = 0;
        int replayConflicts = 0;
        int expiredConflicts = 0;
        int invalidConflicts = 0;
        for (int i = 0; i < 200; i += 50) {
            List<SyncAccessUseCase.Event> batch = events.subList(i, i + 50);
            SyncAccessUseCase.Report r = useCase.sync(neighborhoodId, guardId, batch);
            accepted += r.accepted();
            for (SyncAccessUseCase.Conflict c : r.conflicts()) {
                switch (c.reason()) {
                    case REPLAY -> replayConflicts++;
                    case EXPIRED -> expiredConflicts++;
                    case INVALID_SIGNATURE -> invalidConflicts++;
                    default -> throw new AssertionError("razon inesperada: " + c.reason());
                }
            }
        }

        assertThat(accepted).as("ALLOWED esperado").isEqualTo(130);
        assertThat(replayConflicts).as("REPLAY esperado").isEqualTo(30);
        assertThat(expiredConflicts).as("EXPIRED esperado").isEqualTo(20);
        assertThat(invalidConflicts).as("INVALID_SIGNATURE esperado").isEqualTo(20);

        // Cero perdidas: los 200 eventos se persistieron
        Integer totalLogs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM access_logs", Integer.class);
        assertThat(totalLogs).as("cero perdidas").isEqualTo(200);

        // Cero duplicados: cada clientEventId aparece una vez
        Integer distinct = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT client_event_id) FROM access_logs", Integer.class);
        assertThat(distinct).as("cero duplicados por client_event_id").isEqualTo(200);

        // Todos marcados synced_offline
        Integer offlineMarked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM access_logs WHERE synced_offline = true", Integer.class);
        assertThat(offlineMarked).isEqualTo(200);

        // Cero falsos permitidos: cada ALLOWED referencia una invitacion no expirada y activa
        Integer allowedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM access_logs WHERE result = 'ALLOWED'", Integer.class);
        assertThat(allowedCount).isEqualTo(130);
        Integer falsePositives = jdbc.queryForObject("""
                SELECT COUNT(*) FROM access_logs al
                LEFT JOIN invitations i ON i.id = al.invitation_id
                WHERE al.result = 'ALLOWED'
                  AND (i.id IS NULL OR i.valid_until < al.captured_at OR i.valid_from > al.captured_at)
                """, Integer.class);
        assertThat(falsePositives).as("cero falsos permitidos").isZero();

        // Cada invitacion SINGLE consumida quedo con used_count = 1 (nunca > max_uses)
        Integer overused = jdbc.queryForObject(
                "SELECT COUNT(*) FROM invitations WHERE used_count > max_uses", Integer.class);
        assertThat(overused).as("ninguna invitacion pasa de max_uses").isZero();

        // Las 130 invitaciones validas quedan consumidas (used_count=1); las expiradas no.
        Integer consumed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM invitations WHERE used_count = 1", Integer.class);
        assertThat(consumed).as("las 130 invitaciones validas quedan consumidas").isEqualTo(130);

        // Reintento del batch completo: sync idempotente, cero cambios
        int retryAccepted = 0;
        List<SyncAccessUseCase.Conflict> retryConflicts = new ArrayList<>();
        for (int i = 0; i < 200; i += 50) {
            SyncAccessUseCase.Report r = useCase.sync(neighborhoodId, guardId, events.subList(i, i + 50));
            retryAccepted += r.accepted();
            retryConflicts.addAll(r.conflicts());
        }
        Integer totalLogsAfterRetry = jdbc.queryForObject(
                "SELECT COUNT(*) FROM access_logs", Integer.class);
        assertThat(totalLogsAfterRetry).as("sync idempotente por clientEventId").isEqualTo(200);
        assertThat(retryAccepted).as("reintento reconstruye el mismo veredicto").isEqualTo(130);
        assertThat(retryConflicts).hasSize(70);
    }

    private SyncAccessUseCase.Event event(String token, Instant capturedAt) {
        return new SyncAccessUseCase.Event(
                UUID.randomUUID(), token, gateId, Direction.IN, 0, capturedAt, "ALLOWED");
    }

    private String insertActiveSingle(UUID invitationId) {
        UUID jti = UUID.randomUUID();
        Instant validFrom = Instant.now().minusSeconds(7200);
        Instant validUntil = Instant.now().plusSeconds(7200);
        jdbc.update("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status,
                    guest_name, guest_document, companions, valid_from, valid_until,
                    max_uses, used_count, token_kid, jti
                ) VALUES (?, ?, ?, ?, 'SINGLE', 'ACTIVE', 'Maria', '30123456', 0, ?, ?, 1, 0, ?, ?)
                """,
                invitationId, neighborhoodId, lotId, creatorId,
                Timestamp.from(validFrom), Timestamp.from(validUntil), kid, jti);
        return issuer.issue(new QrTokenIssuer.IssueCommand(
                neighborhoodId, lotId, invitationId, jti, InvitationType.SINGLE,
                validFrom, validUntil, 1));
    }

    private String insertExpiredSingle(UUID invitationId) {
        UUID jti = UUID.randomUUID();
        Instant validFrom = Instant.now().minusSeconds(7200);
        Instant validUntil = Instant.now().minusSeconds(3600);
        jdbc.update("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status,
                    guest_name, guest_document, companions, valid_from, valid_until,
                    max_uses, used_count, token_kid, jti
                ) VALUES (?, ?, ?, ?, 'SINGLE', 'ACTIVE', 'Maria', '30123456', 0, ?, ?, 1, 0, ?, ?)
                """,
                invitationId, neighborhoodId, lotId, creatorId,
                Timestamp.from(validFrom), Timestamp.from(validUntil), kid, jti);
        return issuer.issue(new QrTokenIssuer.IssueCommand(
                neighborhoodId, lotId, invitationId, jti, InvitationType.SINGLE,
                validFrom, validUntil, 1));
    }
}
