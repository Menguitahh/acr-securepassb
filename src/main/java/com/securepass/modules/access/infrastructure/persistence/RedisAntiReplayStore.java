package com.securepass.modules.access.infrastructure.persistence;

import com.securepass.modules.access.application.AntiReplayStore;
import lombok.AllArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@AllArgsConstructor
@Component
public class RedisAntiReplayStore implements AntiReplayStore {

    private final StringRedisTemplate redis;

    @Override
    public ClaimResult claim(UUID jti, int useSeq, String clientEventId, Duration ttl) {
        if (jti == null) throw new IllegalArgumentException("jti requerido");
        if (useSeq < 1) throw new IllegalArgumentException("useSeq >= 1");
        if (clientEventId == null || clientEventId.isBlank()) {
            throw new IllegalArgumentException("clientEventId requerido");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl > 0");
        }
        String key = key(jti, useSeq);
        Boolean claimed = redis.opsForValue().setIfAbsent(key, clientEventId, ttl);
        if (Boolean.TRUE.equals(claimed)) return ClaimResult.FRESH;
        String existing = redis.opsForValue().get(key);
        if (clientEventId.equals(existing)) return ClaimResult.IDEMPOTENT;
        return ClaimResult.REPLAY;
    }

    private static String key(UUID jti, int useSeq) {
        return "replay:" + jti + ":" + useSeq;
    }
}
