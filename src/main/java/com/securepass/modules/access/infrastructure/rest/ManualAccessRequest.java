package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.ManualAccessUseCase;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record ManualAccessRequest(
        @NotBlank @Size(max = 140) String subjectName,
        @NotBlank @Size(max = 20) String subjectDocument,
        @NotNull UUID lotId,
        @NotNull UUID gateId,
        @NotNull UUID clientEventId,
        Instant capturedAt
) {
    public ManualAccessUseCase.Command toCommand(UUID neighborhoodId, UUID guardId) {
        return new ManualAccessUseCase.Command(
                neighborhoodId, gateId, guardId, lotId, subjectName, subjectDocument,
                clientEventId, capturedAt != null ? capturedAt : Instant.now());
    }
}
