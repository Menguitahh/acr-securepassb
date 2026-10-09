package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.SyncAccessUseCase;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.Direction;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SyncAccessRequest(
        @NotNull UUID deviceId,
        @NotEmpty @Size(max = SyncAccessUseCase.MAX_BATCH) @Valid List<Event> events
) {
    public record Event(
            @NotNull UUID clientEventId,
            @NotBlank String token,
            @NotNull UUID gateId,
            @NotNull Direction direction,
            @Min(0) @Max(20) int companions,
            @NotNull Instant capturedAt,
            // String y no enum: uno invalido sale REJECTED de ese evento, no 400
            // del batch (#217). Los valida SyncAccessUseCase.
            @Schema(implementation = SyncAccessUseCase.LocalDecision.class, requiredMode = Schema.RequiredMode.REQUIRED)
            String localDecision,
            @Schema(implementation = DenialReason.class,
                    description = "Obligatorio si y solo si localDecision es DENIED")
            String localDenialReason,
            @Pattern(regexp = "^[0-9]{7,8}$", message = "documentNumber invalido") String documentNumber,
            UUID lotId,
            @Schema(description = "Guardia que capturo el evento (#182). Si es GUARD de este barrio, "
                    + "el log queda a su nombre; si no, al de la sesion que sincroniza.")
            UUID guardId
    ) {
        public SyncAccessUseCase.Event toEvent() {
            return new SyncAccessUseCase.Event(
                    clientEventId, token, gateId, direction, companions, capturedAt,
                    localDecision, localDenialReason, documentNumber, lotId, guardId);
        }
    }

    public List<SyncAccessUseCase.Event> toEvents() {
        return events.stream().map(Event::toEvent).toList();
    }
}
