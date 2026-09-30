package com.securepass.modules.access.application;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.access.domain.AccessDecision;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.invitation.application.InvitationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Import({TestcontainersConfiguration.class, ConsumeAccessConcurrencyTest.InMemoryAntiReplayConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ConsumeAccessConcurrencyTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired ConsumeAccessUseCase useCase;
    @Autowired InvitationRepository invitations;
    @MockBean ValidateAccessUseCase validator;

    UUID neighborhoodId;
    UUID lotId;
    UUID userId;
    UUID invitationId;
    UUID jti;
    Instant validFrom;
    Instant validUntil;

    @BeforeEach
    void seed() {
        neighborhoodId = UUID.randomUUID();
        lotId = UUID.randomUUID();
        userId = UUID.randomUUID();
        invitationId = UUID.randomUUID();
        jti = UUID.randomUUID();
        validFrom = Instant.now().minusSeconds(60);
        validUntil = Instant.now().plusSeconds(3600);

        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, ?, ?)",
                neighborhoodId, "Barrio Test", "b-" + UUID.randomUUID());
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, ?)",
                lotId, neighborhoodId, "L-" + lotId.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name)
                VALUES (?, ?, ?, ?)
                """, userId, "u-" + UUID.randomUUID() + "@t.com", "hash", "Titular");
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

        when(validator.validate(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any())).thenReturn(
                new AccessDecision.Allowed(invitationId, jti, "Maria", "M4-L12", 0));
    }

    @Test
    void dosHilosConcurrentesConsumenUnaSolaVez() throws Exception {
        int threads = 2;
        CyclicBarrier ready = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            List<Future<AccessDecision>> futures = List.of(
                    pool.submit(() -> runAtBarrier(ready, done, "evt-A")),
                    pool.submit(() -> runAtBarrier(ready, done, "evt-B")));

            assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();

            List<AccessDecision> results = List.of(futures.get(0).get(), futures.get(1).get());
            long allowed = results.stream().filter(r -> r instanceof AccessDecision.Allowed).count();
            long alreadyUsed = results.stream()
                    .filter(r -> r.equals(AccessDecision.Denied.of(DenialReason.ALREADY_USED, invitationId)))
                    .count();

            assertThat(allowed).as("exactamente un thread debe recibir Allowed").isEqualTo(1);
            assertThat(alreadyUsed).as("el otro debe recibir ALREADY_USED por row lock").isEqualTo(1);

            var stored = invitations.findById(invitationId).orElseThrow();
            assertThat(stored.usedCount()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private AccessDecision runAtBarrier(CyclicBarrier ready, CountDownLatch done, String eventId) {
        try {
            ready.await(10, TimeUnit.SECONDS);
            return useCase.consume(java.util.UUID.randomUUID(), "token-mockeado", Instant.now(), eventId, true, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            done.countDown();
        }
    }

    @TestConfiguration
    static class InMemoryAntiReplayConfig {

        @Bean
        @Primary
        AntiReplayStore inMemoryAntiReplayStore() {
            ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
            return (jti, useSeq, clientEventId, ttl) -> {
                String key = jti + ":" + useSeq;
                String prior = store.putIfAbsent(key, clientEventId);
                if (prior == null) return AntiReplayStore.ClaimResult.FRESH;
                return clientEventId.equals(prior)
                        ? AntiReplayStore.ClaimResult.IDEMPOTENT
                        : AntiReplayStore.ClaimResult.REPLAY;
            };
        }
    }
}
