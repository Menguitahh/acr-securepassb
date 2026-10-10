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
        Instant capturedAt,
        // US-24 / WBS-36: la visita imprevista no tiene invitacion de donde
        // sacar los acompanantes: los carga el guardia. Null = 0.
        @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(10) Integer companions
) {
    public ManualAccessUseCase.Command toCommand(UUID neighborhoodId, UUID guardId) {
        return new ManualAccessUseCase.Command(
                neighborhoodId, gateId, guardId, lotId, subjectName, subjectDocument,
                clientEventId, capturedAt != null ? capturedAt : Instant.now(),
                companions == null ? 0 : companions);
    }
}
