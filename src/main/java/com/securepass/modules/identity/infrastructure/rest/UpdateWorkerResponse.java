package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.UpdateWorkerUseCase;

import java.util.List;
import java.util.UUID;

public record UpdateWorkerResponse(
        UUID userId,
        String fullName,
        String email,
        String documentNumber,
        String service,
        List<Integer> weekdays,
        String dailyFrom,
        String dailyUntil,
        boolean active,
        UpdateWorkerUseCase.Outcome outcome
) {
    public static UpdateWorkerResponse from(UpdateWorkerUseCase.Result r) {
        return new UpdateWorkerResponse(
                r.userId(), r.fullName(), r.email(), r.documentNumber(),
                r.service(),
                r.schedule().weekdays(),
                r.schedule().dailyFrom().toString(),
                r.schedule().dailyUntil().toString(),
                r.active(),
                r.outcome());
    }
}
