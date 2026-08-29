package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.domain.Minor;

import java.time.LocalDate;
import java.util.UUID;

public record MinorResponse(
        UUID id,
        UUID lotId,
        String fullName,
        String documentNumber,
        LocalDate birthDate,
        String relation,
        String notes) {

    public static MinorResponse from(Minor m) {
        return new MinorResponse(
                m.id(), m.lotId(), m.fullName(), m.documentNumber(),
                m.birthDate(), m.relation(), m.notes());
    }
}
