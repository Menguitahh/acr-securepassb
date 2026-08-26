package com.securepass.modules.invitation.infrastructure.rest;

import com.securepass.modules.invitation.application.ExtendInvitationUseCase;

import java.time.Instant;
import java.util.UUID;

public record ExtendInvitationResponse(
        UUID id,
        Instant validUntil,
        int extensionCount,
        int maxExtensions
) {
    public static ExtendInvitationResponse from(ExtendInvitationUseCase.Result r) {
        return new ExtendInvitationResponse(
                r.invitation().id(),
                r.invitation().validUntil(),
                r.extensionCount(),
                r.maxExtensions());
    }
}
