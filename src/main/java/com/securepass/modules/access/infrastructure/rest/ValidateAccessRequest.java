package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.RecordAccessAttemptUseCase;
import com.securepass.modules.access.domain.Direction;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public record ValidateAccessRequest(
        @NotBlank String token,
        @NotNull UUID gateId,
        @NotNull Direction direction,
        @Min(0) @Max(20) int companions,
        @NotNull Instant capturedAt,
        @NotNull UUID clientEventId,
        // #217: lote que eligio el guardia (NEEDS_LOT_SELECTION), o null.
        UUID lotId
) {

    public RecordAccessAttemptUseCase.Command toCommand(UUID neighborhoodId, UUID guardId) {
        return new RecordAccessAttemptUseCase.Command(
                neighborhoodId, gateId, guardId, token, direction,
                companions, capturedAt, clientEventId, false, lotId);
    }
}
