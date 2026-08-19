package com.securepass.modules.invitation.application;

import java.time.Instant;
import java.util.UUID;

public record RevokedEntry(UUID jti, Instant revokedAt) {

    public RevokedEntry {
        if (jti == null) throw new IllegalArgumentException("jti requerido");
        if (revokedAt == null) throw new IllegalArgumentException("revokedAt requerido");
    }
}
