package com.securepass.modules.invitation.application;

import java.util.UUID;

public record ResidentLot(
        UUID lotId,
        String lotCode,
        UUID neighborhoodId,
        String neighborhoodName
) {
    public ResidentLot {
        if (lotId == null) throw new IllegalArgumentException("lotId requerido");
        if (lotCode == null || lotCode.isBlank()) throw new IllegalArgumentException("lotCode requerido");
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        if (neighborhoodName == null || neighborhoodName.isBlank()) {
            throw new IllegalArgumentException("neighborhoodName requerido");
        }
    }
}
