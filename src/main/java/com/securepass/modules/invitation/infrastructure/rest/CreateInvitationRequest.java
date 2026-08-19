package com.securepass.modules.invitation.infrastructure.rest;

import com.securepass.modules.invitation.application.CreateInvitationUseCase;
import com.securepass.modules.invitation.domain.InvitationType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record CreateInvitationRequest(
        @NotNull UUID lotId,
        @NotNull InvitationType type,
        @NotBlank @Size(max = 140) String guestName,
        @Size(max = 20) String guestDocument,
        @Min(0) @Max(20) int companions,
        @Size(max = 280) String notes,
        @NotNull Instant validFrom,
        @NotNull Instant validUntil,
        List<Integer> weekdays,
        LocalTime dailyFrom,
        LocalTime dailyUntil,
        @Min(1) int maxUses
) {

    public CreateInvitationUseCase.Command toCommand() {
        return new CreateInvitationUseCase.Command(
                lotId, type, guestName, guestDocument, companions, notes,
                validFrom, validUntil, weekdays, dailyFrom, dailyUntil, maxUses);
    }
}
