package com.securepass.modules.invitation.domain;

import java.time.Instant;
import java.util.UUID;

public record SigningKey(
        UUID id,
        UUID neighborhoodId,
        String kid,
        byte[] publicKey,
        String privateKeyEnc,
        boolean active,
        Instant createdAt,
        Instant rotatedAt
) {
    public SigningKey {
        if (id == null) throw new IllegalArgumentException("id requerido");
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        if (kid == null || kid.isBlank()) throw new IllegalArgumentException("kid requerido");
        if (kid.length() > 16) throw new IllegalArgumentException("kid excede 16 caracteres");
        if (publicKey == null || publicKey.length != 32) throw new IllegalArgumentException("publicKey debe ser 32 bytes");
        if (privateKeyEnc == null || privateKeyEnc.isBlank()) throw new IllegalArgumentException("privateKeyEnc requerido");
        if (createdAt == null) throw new IllegalArgumentException("createdAt requerido");
    }
}
