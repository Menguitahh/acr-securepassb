package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.PendingActivation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PendingActivationResponse(UUID userId, String fullName, String email, List<String> roles,
                                        List<String> lotCodes, Instant createdAt) {

    public static PendingActivationResponse from(PendingActivation p) {
        return new PendingActivationResponse(p.userId(), p.fullName(), p.email(), p.roles(), p.lotCodes(),
                p.createdAt());
    }
}
