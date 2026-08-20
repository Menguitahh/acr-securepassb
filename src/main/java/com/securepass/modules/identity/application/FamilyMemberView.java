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
        boolean active
) {
    public FamilyMemberView {
        if (userId == null) throw new IllegalArgumentException("userId requerido");
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("fullName requerido");
        }
    }
}
