package com.securepass.modules.access.application;

import java.time.Instant;
import java.util.UUID;

public record OpenStayView(
        UUID id,
        String subjectName,
        String lotCode,
        Instant enteredAt,
        Instant expectedUntil,
        boolean warned,
        Instant exitRequestedAt
) {
    public OpenStayView {
        if (id == null) throw new IllegalArgumentException("id requerido");
        if (subjectName == null || subjectName.isBlank()) throw new IllegalArgumentException("subjectName requerido");
        if (enteredAt == null) throw new IllegalArgumentException("enteredAt requerido");
        if (expectedUntil == null) throw new IllegalArgumentException("expectedUntil requerido");
    }
}
