package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.Suspension;

import java.time.Instant;
import java.util.UUID;

/**
 * securepassb#121: neighborhoodName, reason y suspendedAt son null en una
 * suspension anterior a #121. ownNeighborhood: la puso el barrio del admin
 * que consulta, y solo entonces la puede levantar.
 */
public record SuspensionResponse(String neighborhoodName, String reason, Instant suspendedAt,
                                 boolean ownNeighborhood) {

    static SuspensionResponse from(Suspension s, UUID adminNeighborhoodId) {
        boolean own = s.neighborhoodId() == null || s.neighborhoodId().equals(adminNeighborhoodId);
        return new SuspensionResponse(s.neighborhoodName(), s.reason(), s.suspendedAt(), own);
    }
}
