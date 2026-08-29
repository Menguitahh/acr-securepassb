package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.AddWorkerUseCase;
import com.securepass.modules.identity.domain.UserStatus;

import java.util.List;
import java.util.UUID;

public record AddWorkerResponse(
        UUID userId,
        String fullName,
        String email,
        String service,
        List<Integer> weekdays,
        String dailyFrom,
        String dailyUntil,
        boolean active,
        boolean reused,
        boolean changed,
        AddWorkerUseCase.Outcome outcome,
        UserStatus userStatus
) {
    public static AddWorkerResponse from(AddWorkerUseCase.Result r) {
        return new AddWorkerResponse(
                r.userId(), r.fullName(), r.email(),
                r.service(),
                r.schedule().weekdays(),
                r.schedule().dailyFrom().toString(),
                r.schedule().dailyUntil().toString(),
                r.permissionActive(), r.reused(), r.changed(),
                r.outcome(), r.userStatus());
    }
}
