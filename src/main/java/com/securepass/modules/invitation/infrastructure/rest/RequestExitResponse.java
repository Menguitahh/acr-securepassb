package com.securepass.modules.invitation.infrastructure.rest;

import com.securepass.modules.invitation.application.RequestGuestExitUseCase;

import java.time.Instant;
import java.util.UUID;

public record RequestExitResponse(UUID invitationId, Instant exitRequestedAt) {
    public static RequestExitResponse from(RequestGuestExitUseCase.Result r) {
        return new RequestExitResponse(r.invitationId(), r.exitRequestedAt());
    }
}
