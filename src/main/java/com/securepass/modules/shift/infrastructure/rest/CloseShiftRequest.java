package com.securepass.modules.shift.infrastructure.rest;

import jakarta.validation.constraints.Size;

public record CloseShiftRequest(@Size(max = 1000) String handoverNotes) {}
