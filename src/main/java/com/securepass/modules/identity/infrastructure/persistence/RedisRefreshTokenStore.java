package com.securepass.modules.identity.infrastructure.persistence;

import com.securepass.modules.identity.application.RefreshTokenStore;
import com.securepass.shared.security.Sha256Hasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private static final String ACTIVE = "active";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisRefreshTokenStore(
            StringRedisTemplate redis,
            @Value("${securepass.refresh.ttl-days:30}") long ttlDays) {
        if (ttlDays <= 0) throw new IllegalStateException("securepass.refresh.ttl-days debe ser > 0");
        this.redis = redis;
        this.ttl = Duration.ofDays(ttlDays);
    }

    @Override
    public void save(String rawToken, UUID familyId, UUID userId) {
        String hash = Sha256Hasher.hexOf(rawToken);
        redis.opsForValue().set(refreshKey(hash), familyId + ":" + userId, ttl);
        redis.opsForValue().set(familyKey(familyId), ACTIVE, ttl);
    }

    @Override
    public ConsumeResult consume(String rawToken) {
        String hash = Sha256Hasher.hexOf(rawToken);
        String value = redis.opsForValue().getAndDelete(refreshKey(hash));
        if (value != null) {
            int sep = value.indexOf(':');
            if (sep < 0) return new Invalid();
            UUID familyId = UUID.fromString(value.substring(0, sep));
            UUID userId = UUID.fromString(value.substring(sep + 1));
            redis.opsForValue().set(consumedKey(hash), familyId.toString(), ttl);
            String family = redis.opsForValue().get(familyKey(familyId));
            if (!ACTIVE.equals(family)) return new Invalid();
            return new Consumed(familyId, userId);
        }
        String consumed = redis.opsForValue().get(consumedKey(hash));
        if (consumed != null) return new ReplayDetected(UUID.fromString(consumed));
        return new Invalid();
    }

    @Override
    public void revokeFamily(UUID familyId) {
        redis.delete(familyKey(familyId));
    }

    private static String refreshKey(String hash) {
        return "refresh:" + hash;
    }

    private static String consumedKey(String hash) {
        return "consumed:" + hash;
    }

    private static String familyKey(UUID familyId) {
        return "family:" + familyId;
    }
}
