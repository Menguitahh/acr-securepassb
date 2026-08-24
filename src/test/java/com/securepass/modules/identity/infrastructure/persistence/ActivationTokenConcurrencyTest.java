package com.securepass.modules.identity.infrastructure.persistence;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.application.ActivationTokenGenerator;
import com.securepass.modules.identity.application.ActivationTokenRepository;
import com.securepass.modules.identity.domain.ActivationToken;
import com.securepass.modules.identity.domain.TokenPurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WBS-101: garantia de "un solo uso" del token bajo concurrencia. Mismo
 * tipo de agujero que un anti-replay que falla (ver
 * ConsumeAccessConcurrencyTest de WBS-23): dos hilos que consumen el
 * mismo token en paralelo, exactamente uno debe ganar. El UPDATE de
 * markConsumed cierra la carrera a nivel de fila.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ActivationTokenConcurrencyTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired ActivationTokenRepository repo;

    UUID userId;
    UUID tokenId;

    @BeforeEach
    void seed() {
        userId = UUID.randomUUID();
        tokenId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """, userId, "u-" + UUID.randomUUID() + "@t.com", "hash", "Titular");

        ActivationTokenGenerator.Generated g = new ActivationTokenGenerator().generate();
        Instant now = Instant.now();
        repo.save(new ActivationToken(
                tokenId, userId, TokenPurpose.ACTIVATION, g.hash(),
                now.plusSeconds(3600), null, now));
    }

    @Test
    void dosHilosConcurrentesConsumenElTokenUnaSolaVez() throws Exception {
        int threads = 2;
        CyclicBarrier ready = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            List<Future<Boolean>> futures = List.of(
                    pool.submit(() -> runAtBarrier(ready, done)),
                    pool.submit(() -> runAtBarrier(ready, done)));

            assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();

            long ganadores = futures.stream()
                    .map(f -> {
                        try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); }
                    })
                    .filter(Boolean::booleanValue)
                    .count();

            assertThat(ganadores).as("solo un hilo debe consumir el token").isEqualTo(1);
            assertThat(repo.findByHash(loadHash())).get()
                    .extracting(ActivationToken::isConsumed).isEqualTo(true);
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean runAtBarrier(CyclicBarrier ready, CountDownLatch done) {
        try {
            ready.await(10, TimeUnit.SECONDS);
            return repo.markConsumed(tokenId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            done.countDown();
        }
    }

    private byte[] loadHash() {
        return jdbc.queryForObject(
                "SELECT token_hash FROM activation_tokens WHERE id = ?",
                byte[].class, tokenId);
    }
}
