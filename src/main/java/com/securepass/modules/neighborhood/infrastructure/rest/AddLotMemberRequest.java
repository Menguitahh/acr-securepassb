package com.securepass.modules.neighborhood.infrastructure.rest;

import com.securepass.modules.neighborhood.application.AddLotMemberUseCase;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record AddLotMemberRequest(
        @NotNull UUID userId,
        @Size(max = 30) String relation,
        boolean isOwner,
        boolean canInvite
) {
    public AddLotMemberUseCase.Command toCommand() {
        return new AddLotMemberUseCase.Command(userId, relation, isOwner, canInvite);
    }
}
