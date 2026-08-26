package com.securepass.shared.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Sliding-window rate limiter con ZSET de Redis.
 *
 * key -> ZSET donde cada miembro es un UUID y su score es el epoch ms del
 * evento. En cada consulta se descartan los eventos previos a `now - window`,
 * se agrega el actual con ttl del ZSET = window, y se cuenta. Si supera el
 * limite, se pide `Retry-After` estimado como (score mas viejo dentro de la
 * ventana + window) - now.
 */
@Component
public class RedisSlidingWindowRateLimiter implements RateLimiter {

    private final StringRedisTemplate redis;

    public RedisSlidingWindowRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<Duration> tryAcquire(String key, int limit, Duration window) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key requerido");
        if (limit < 1) throw new IllegalArgumentException("limit >= 1");
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window > 0");
        }

        String zkey = "rate:" + key;
        long nowMs = Instant.now().toEpochMilli();
        long windowStart = nowMs - window.toMillis();

        redis.opsForZSet().removeRangeByScore(zkey, 0, windowStart);
        Long count = redis.opsForZSet().zCard(zkey);
        long current = count == null ? 0L : count;

        if (current >= limit) {
            Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> oldest =
                    redis.opsForZSet().rangeWithScores(zkey, 0, 0);
            long retryMs = window.toMillis();
            if (oldest != null && !oldest.isEmpty()) {
                Double score = oldest.iterator().next().getScore();
                if (score != null) {
                    retryMs = Math.max(1, (long) (score.doubleValue() + window.toMillis() - nowMs));
                }
            }
            return Optional.of(Duration.ofMillis(retryMs));
        }

        redis.opsForZSet().add(zkey, UUID.randomUUID().toString(), nowMs);
        redis.expire(zkey, window);
        return Optional.empty();
    }
}
