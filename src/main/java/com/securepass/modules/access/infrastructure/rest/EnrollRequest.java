package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.EnrollGuestUseCase;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

public record EnrollRequest(
        @NotBlank String token,
        @NotNull @Pattern(regexp = "^[0-9]{7,8}$", message = "documentNumber invalido") String documentNumber,
        @NotNull UUID gateId,
        @NotNull UUID clientEventId,
        Instant capturedAt,
        // #219: lote elegido de NEEDS_ENROLLMENT.WORKER; obligatorio si hubo mas de uno.
        UUID lotId
) {
    public EnrollGuestUseCase.Command toCommand(UUID neighborhoodId, UUID guardId) {
        return new EnrollGuestUseCase.Command(
                neighborhoodId, gateId, guardId, token, documentNumber,
                clientEventId, capturedAt != null ? capturedAt : Instant.now(), false, lotId);
    }
}
