package com.securepass.modules.access.infrastructure.rest;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.securepass.modules.access.application.SyncAccessUseCase;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.Direction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
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
            @NotNull SyncAccessUseCase.LocalDecision localDecision,
            DenialReason localDenialReason,
            @Pattern(regexp = "^[0-9]{7,8}$", message = "documentNumber invalido") String documentNumber,
            UUID lotId
    ) {
        public SyncAccessUseCase.Event toEvent() {
            return new SyncAccessUseCase.Event(
                    clientEventId, token, gateId, direction, companions, capturedAt,
                    localDecision, localDenialReason, documentNumber, lotId);
        }

        @JsonIgnore
        @AssertTrue(message = "localDenialReason requerido si y solo si localDecision es DENIED")
        public boolean isLocalDenialReasonValid() {
            return localDecision == null
                    || SyncAccessUseCase.localDenialReasonConsistent(localDecision, localDenialReason);
        }
    }

    public List<SyncAccessUseCase.Event> toEvents() {
        return events.stream().map(Event::toEvent).toList();
    }
}
