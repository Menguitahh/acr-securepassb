package com.securepass.modules.neighborhood.domain;

import java.time.Instant;
import java.util.UUID;

public record LotMember(
        UUID id,
        UUID lotId,
        UUID userId,
        String relation,
        boolean isOwner,
        boolean canInvite,
        boolean active,
        Instant createdAt
) {
    private static final int RELATION_MAX = 30;

    public LotMember {
        if (id == null) throw new IllegalArgumentException("id requerido");
        if (lotId == null) throw new IllegalArgumentException("lotId requerido");
        if (userId == null) throw new IllegalArgumentException("userId requerido");
        if (relation != null && relation.length() > RELATION_MAX) {
            throw new IllegalArgumentException("relation excede " + RELATION_MAX);
        }
        if (createdAt == null) throw new IllegalArgumentException("createdAt requerido");
        // Titular siempre con permiso de invitar (BR-02).
        if (isOwner && !canInvite) canInvite = true;
    }
}
