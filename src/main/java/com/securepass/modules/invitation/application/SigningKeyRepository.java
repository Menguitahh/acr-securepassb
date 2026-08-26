package com.securepass.modules.invitation.application;

import com.securepass.modules.invitation.domain.SigningKey;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SigningKeyRepository {
    void save(SigningKey key);

    Optional<SigningKey> findActive(UUID neighborhoodId);

    Optional<SigningKey> findByKid(UUID neighborhoodId, String kid);

    void markInactive(UUID id, Instant rotatedAt);
}
