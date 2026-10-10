package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.PersonSummary;

import java.util.List;
import java.util.UUID;

/** securepassb#121: persona del barrio en Padron. suspension null si no esta suspendida. */
public record PersonResponse(UUID userId, String fullName, String documentNumber, String email, String status,
                             List<String> roles, List<String> lotCodes, SuspensionResponse suspension) {

    public static PersonResponse from(PersonSummary p, UUID adminNeighborhoodId) {
        return new PersonResponse(p.userId(), p.fullName(), p.documentNumber(), p.email(), p.status().name(),
                p.roles(), p.lotCodes(),
                p.suspension() == null ? null : SuspensionResponse.from(p.suspension(), adminNeighborhoodId));
    }
}
