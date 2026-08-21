package com.securepass.shared.ratelimit;

import com.securepass.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RedisSlidingWindowRateLimiterTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    RateLimiter limiter;

    @Test
    void aceptaHastaElLimiteYBloqueaElExcedente() {
        String key = "test-" + UUID.randomUUID();
        Duration window = Duration.ofMinutes(1);

        for (int i = 1; i <= 3; i++) {
            Optional<Duration> retry = limiter.tryAcquire(key, 3, window);
            assertThat(retry).as("intento %d dentro del limite", i).isEmpty();
        }
        Optional<Duration> blocked = limiter.tryAcquire(key, 3, window);
        assertThat(blocked).isPresent();
        assertThat(blocked.get()).isPositive().isLessThanOrEqualTo(window);
    }

    @Test
    void llavesDistintasNoSePisan() {
        String a = "test-" + UUID.randomUUID();
        String b = "test-" + UUID.randomUUID();
        Duration window = Duration.ofMinutes(1);

        assertThat(limiter.tryAcquire(a, 1, window)).isEmpty();
        assertThat(limiter.tryAcquire(a, 1, window)).isPresent();
        assertThat(limiter.tryAcquire(b, 1, window)).isEmpty();
    }
}
