package com.securepass.modules.identity.application;

import java.util.UUID;

public record FamilyMemberView(
        UUID userId,
        String fullName,
        String email,
        String phone,
        String relation,
        boolean isOwner,
        boolean canInvite,
        boolean active,
        // US-54: PENDING_ACTIVATION muestra "Reenviar activacion".
        com.securepass.modules.identity.domain.UserStatus userStatus
) {
    public FamilyMemberView {
        if (userId == null) throw new IllegalArgumentException("userId requerido");
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("fullName requerido");
        }
    }
}
