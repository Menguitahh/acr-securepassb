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

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SyncAccessFirstCapturedWinsTest {

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
    UUID invitationId;
    UUID jti;
    String token;

    @BeforeEach
    void seed() {
        neighborhoodId = UUID.randomUUID();
        lotId = UUID.randomUUID();
        gateId = UUID.randomUUID();
        guardId = UUID.randomUUID();
        invitationId = UUID.randomUUID();
        jti = UUID.randomUUID();

        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, ?, ?)",
                neighborhoodId, "Barrio", "b-" + UUID.randomUUID());
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, ?)",
                lotId, neighborhoodId, "L-" + lotId.toString().substring(0, 8));
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, ?, 'BOTH')",
                gateId, neighborhoodId, "G-" + gateId.toString().substring(0, 8));
        UUID creatorId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, ?, ?)",
                guardId, "g-" + UUID.randomUUID() + "@t.com", "hash", "Guardia");
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, ?, ?)",
                creatorId, "u-" + UUID.randomUUID() + "@t.com", "hash", "Titular");

        Ed25519KeyPair kp = Ed25519KeyFactory.generate();
        String kid = "kid-" + UUID.randomUUID().toString().substring(0, 6);
        signingKeys.save(new SigningKey(
                UUID.randomUUID(), neighborhoodId, kid,
                kp.publicKey(), cipher.encrypt(kp.privateKey()),
                true, Instant.now(), null));

        Instant validFrom = Instant.now().minusSeconds(3600);
        Instant validUntil = Instant.now().plusSeconds(3600);
        jdbc.update("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status,
                    guest_name, guest_document, companions, valid_from, valid_until,
                    max_uses, used_count, token_kid, jti
                ) VALUES (?, ?, ?, ?, 'SINGLE', 'ACTIVE', 'Maria', '30123456', 0, ?, ?, 1, 0, ?, ?)
                """,
                invitationId, neighborhoodId, lotId, creatorId,
                Timestamp.from(validFrom), Timestamp.from(validUntil), kid, jti);

        token = issuer.issue(new QrTokenIssuer.IssueCommand(
                neighborhoodId, lotId, invitationId, jti, InvitationType.SINGLE,
                validFrom, validUntil, 1, null, null, null));
    }

    @Test
    void masTempranoGanaAllowedYSegundoQuedaComoReplay() {
        UUID clientEarly = UUID.randomUUID();
        UUID clientLate = UUID.randomUUID();
        Instant tEarly = Instant.now().minusSeconds(120);
        Instant tLate = Instant.now().minusSeconds(30);

        SyncAccessUseCase.Event late = new SyncAccessUseCase.Event(
                clientLate, token, gateId, Direction.IN, 0, tLate, "ALLOWED");
        SyncAccessUseCase.Event early = new SyncAccessUseCase.Event(
                clientEarly, token, gateId, Direction.IN, 0, tEarly, "ALLOWED");

        SyncAccessUseCase.Report report = useCase.sync(neighborhoodId, guardId, List.of(late, early));

        assertThat(report.accepted()).isEqualTo(1);
        assertThat(report.conflicts()).hasSize(1);
        assertThat(report.conflicts().get(0).clientEventId()).isEqualTo(clientLate);
        assertThat(report.conflicts().get(0).reason().name()).isEqualTo("REPLAY");

        Map<String, Object> earlyRow = jdbc.queryForMap(
                "SELECT result, denial_reason, synced_offline FROM access_logs WHERE client_event_id = ?",
                clientEarly);
        assertThat(earlyRow.get("result")).isEqualTo("ALLOWED");
        assertThat(earlyRow.get("synced_offline")).isEqualTo(true);

        Map<String, Object> lateRow = jdbc.queryForMap(
                "SELECT result, denial_reason, synced_offline FROM access_logs WHERE client_event_id = ?",
                clientLate);
        assertThat(lateRow.get("result")).isEqualTo("DENIED");
        assertThat(lateRow.get("denial_reason")).isEqualTo("REPLAY");
        assertThat(lateRow.get("synced_offline")).isEqualTo(true);

        Integer usedCount = jdbc.queryForObject(
                "SELECT used_count FROM invitations WHERE id = ?", Integer.class, invitationId);
        assertThat(usedCount).isEqualTo(1);
    }
}
