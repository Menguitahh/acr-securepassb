package com.securepass.modules.shift.infrastructure.rest;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record OpenShiftRequest(
        @NotNull UUID gateId,
        @Size(max = 1000) String handoverNotes
) {}
