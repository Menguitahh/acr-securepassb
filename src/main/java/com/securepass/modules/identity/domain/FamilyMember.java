package com.securepass.modules.identity.domain;

import java.util.UUID;

public record FamilyMember(
        UUID userId,
        UUID lotId,
        String relation,
        boolean isOwner,
        boolean canInvite,
        boolean active
) {
    private static final int RELATION_MAX = 30;

    public FamilyMember {
        if (userId == null) throw new IllegalArgumentException("userId requerido");
        if (lotId == null) throw new IllegalArgumentException("lotId requerido");
        if (relation != null && relation.length() > RELATION_MAX) {
            throw new IllegalArgumentException("relation excede " + RELATION_MAX);
        }
        if (isOwner && !active) {
            throw new IllegalArgumentException("el titular no puede estar inactivo");
        }
    }
}
