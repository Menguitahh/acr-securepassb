package com.securepass.shared.config;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.application.NeighborhoodNameQuery;
import com.securepass.modules.invitation.application.SigningKeyIssuer;
import com.securepass.modules.invitation.application.SigningKeyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** @Cacheable: lo cacheado se sirve sin ir a la base, y rotar una clave vacia su cache. */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CacheConfigTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired NeighborhoodNameQuery names;
    @Autowired SigningKeyIssuer issuer;
    @Autowired SigningKeyRepository keys;
    @Autowired CacheManager caches;

    @Test
    void elNombreDelBarrioSaleDelCache() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'Los Robles', ?)", id, "r-" + id);

        assertThat(names.findName(id)).isEqualTo("Los Robles");
        jdbc.update("UPDATE neighborhoods SET name = 'Otro' WHERE id = ?", id);
        assertThat(names.findName(id)).isEqualTo("Los Robles");
    }

    @Test
    void laClavePorKidSeCacheaYRotarLaVacia() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'B', ?)", id, "b-" + id);
        String kid = issuer.issueForNeighborhood(id).kid();
        String key = id + ":" + kid;

        assertThat(keys.findByKid(id, "no-existe")).isEmpty();
        assertThat(caches.getCache(CacheConfig.SIGNING_KEYS_BY_KID).get(id + ":no-existe")).isNull();

        var found = keys.findByKid(id, kid).orElseThrow();
        assertThat(caches.getCache(CacheConfig.SIGNING_KEYS_BY_KID).get(key)).isNotNull();

        keys.markInactive(found.id(), Instant.now());
        assertThat(caches.getCache(CacheConfig.SIGNING_KEYS_BY_KID).get(key)).isNull();
    }
}
