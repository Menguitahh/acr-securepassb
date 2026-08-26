package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.invitation.application.RevokedEntry;

import java.time.Instant;
import java.util.UUID;

public record RevokedTokenResponse(UUID jti, Instant revokedAt) {

    public static RevokedTokenResponse from(RevokedEntry entry) {
        return new RevokedTokenResponse(entry.jti(), entry.revokedAt());
    }
}
