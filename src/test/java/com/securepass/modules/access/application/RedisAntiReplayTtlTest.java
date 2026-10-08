package com.securepass.modules.access.application;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.access.domain.AccessDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RedisAntiReplayTtlTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ConsumeAccessUseCase useCase;
    @Autowired StringRedisTemplate redisTemplate;
    @MockBean ValidateAccessUseCase validator;

    UUID neighborhoodId;
    UUID lotId;
    UUID userId;
    UUID invitationId;
    UUID jti;

    @BeforeEach
    void setUp() {
        neighborhoodId = UUID.randomUUID();
        lotId = UUID.randomUUID();
        userId = UUID.randomUUID();
        invitationId = UUID.randomUUID();
        jti = UUID.randomUUID();

        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, ?, ?)",
                neighborhoodId, "Barrio Test", "b-" + UUID.randomUUID());
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, ?)",
                lotId, neighborhoodId, "L-" + lotId.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name)
                VALUES (?, ?, ?, ?)
                """, userId, "u-" + UUID.randomUUID() + "@t.com", "hash", "Titular");

        when(validator.validate(any(), any(), any(), any(), any())).thenReturn(
                new AccessDecision.Allowed(invitationId, jti, "Maria", "M4-L12", 0));
    }

    private void seedInvitation(Instant validFrom, Instant validUntil) {
        jdbc.update("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status,
                    guest_name, guest_document, companions,
                    valid_from, valid_until, max_uses, used_count,
                    token_kid, jti
                ) VALUES (?, ?, ?, ?, 'SINGLE', 'ACTIVE', 'Maria', '30123456', 0, ?, ?, 1, 0, 'kid-1', ?)
                """,
                invitationId, neighborhoodId, lotId, userId,
                Timestamp.from(validFrom), Timestamp.from(validUntil), jti);
    }

    @Test
    void claimGuardaClaveConClientEventIdYTtlPositivo() {
        Instant now = Instant.now();
        seedInvitation(now.minus(Duration.ofMinutes(5)), now.plus(Duration.ofHours(6)));

        AccessDecision result = useCase.consume(java.util.UUID.randomUUID(), "token", now, "evt-1", null);

        assertThat(result).isInstanceOf(AccessDecision.Allowed.class);
        String key = "replay:" + jti + ":1";
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("evt-1");
        Long ttlSeconds = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttlSeconds).isPositive();
    }

    @Test
    void ttlRespetaPisoDeCincoMinutosCuandoValidUntilYaPaso() {
        Instant now = Instant.now();
        seedInvitation(now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)));

        AccessDecision result = useCase.consume(java.util.UUID.randomUUID(), "token", now, "evt-2", null);

        assertThat(result).isInstanceOf(AccessDecision.Allowed.class);
        String key = "replay:" + jti + ":1";
        Long ttlSeconds = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttlSeconds)
                .as("TTL debe respetar piso de 300s cuando validUntil ya paso")
                .isBetween(290L, 300L);
    }
}
