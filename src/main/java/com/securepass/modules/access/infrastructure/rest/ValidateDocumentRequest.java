package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.RecordAccessAttemptUseCase;
import com.securepass.modules.access.domain.Direction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

/**
 * securepassf#119: ingreso de un trabajador sin QR por el DNI que escanea el
 * guardia. Misma respuesta que /access/validate.
 */
public record ValidateDocumentRequest(
        @NotNull @Pattern(regexp = "^[0-9]{7,8}$", message = "documentNumber invalido") String documentNumber,
        @NotNull UUID gateId,
        @NotNull Direction direction,
        @NotNull Instant capturedAt,
        @NotNull UUID clientEventId,
        UUID lotId
) {

    public RecordAccessAttemptUseCase.Command toCommand(UUID neighborhoodId, UUID guardId) {
        return new RecordAccessAttemptUseCase.Command(
                neighborhoodId, gateId, guardId, null, direction,
                0, capturedAt, clientEventId, false, lotId, documentNumber);
    }
}
