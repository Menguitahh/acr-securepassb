package com.securepass.modules.identity.domain;

import java.util.UUID;

public record Membership(
        UUID id,
        UUID userId,
        UUID neighborhoodId,
        Role role,
        boolean active
) {
    public Membership {
        if (id == null) throw new IllegalArgumentException("id requerido");
        if (userId == null) throw new IllegalArgumentException("userId requerido");
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        if (role == null) throw new IllegalArgumentException("role requerido");
    }
}
