package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.FamilyMemberView;

import java.util.UUID;

public record FamilyMemberResponse(
        UUID userId,
        String fullName,
        String email,
        String phone,
        String relation,
        boolean isOwner,
        boolean canInvite,
        boolean active
) {
    public static FamilyMemberResponse from(FamilyMemberView view) {
        return new FamilyMemberResponse(
                view.userId(), view.fullName(), view.email(), view.phone(),
                view.relation(), view.isOwner(), view.canInvite(), view.active());
    }
}
