package com.securepass.modules.identity.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Token de un solo uso para activacion de cuenta o recuperacion de
 * contrasena (WBS-101, US-51/US-53). En el dominio guardamos siempre el
 * HASH del token, nunca el valor en claro: la persistencia y las lecturas
 * no deben poder reconstruirlo.
 *
 * El array {@code tokenHash} se copia en la entrada y salida para preservar
 * la inmutabilidad del record.
 */
public record ActivationToken(
        UUID id,
        UUID userId,
        TokenPurpose purpose,
        byte[] tokenHash,
        Instant expiresAt,
        Instant consumedAt,
        Instant createdAt
) {
    private static final int HASH_LEN = 32;

    public ActivationToken {
        if (id == null) throw new IllegalArgumentException("id requerido");
        if (userId == null) throw new IllegalArgumentException("userId requerido");
        if (purpose == null) throw new IllegalArgumentException("purpose requerido");
        if (tokenHash == null || tokenHash.length != HASH_LEN) {
            throw new IllegalArgumentException("tokenHash debe ser SHA-256 de 32 bytes");
        }
        if (createdAt == null) throw new IllegalArgumentException("createdAt requerido");
        if (expiresAt == null || !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt debe ser posterior a createdAt");
        }
        tokenHash = tokenHash.clone();
    }

    @Override
    public byte[] tokenHash() {
        return tokenHash.clone();
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isUsable(Instant now) {
        return !isConsumed() && !isExpired(now);
    }
}
