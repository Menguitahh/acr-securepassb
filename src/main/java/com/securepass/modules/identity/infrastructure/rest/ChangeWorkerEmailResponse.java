package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.ChangePendingWorkerEmailUseCase;

import java.util.UUID;

public record ChangeWorkerEmailResponse(UUID userId, String email) {

    public static ChangeWorkerEmailResponse from(ChangePendingWorkerEmailUseCase.Result r) {
        return new ChangeWorkerEmailResponse(r.userId(), r.email());
    }
}
