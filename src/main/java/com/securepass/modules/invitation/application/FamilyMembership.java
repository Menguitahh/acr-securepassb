package com.securepass.modules.invitation.application;

import java.util.UUID;

public record FamilyMembership(UUID userId, UUID lotId, boolean isOwner, boolean canInvite, boolean active) {

    public FamilyMembership {
        if (userId == null) throw new IllegalArgumentException("userId requerido");
        if (lotId == null) throw new IllegalArgumentException("lotId requerido");
    }
}
