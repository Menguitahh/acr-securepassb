package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.UpdateFamilyMemberUseCase;

import java.util.UUID;

/**
 * WBS-112: response del PATCH sobre un miembro. Extiende {@link FamilyMemberResponse}
 * con {@code outcome} y {@code revokedInvitationsCount} para que el mobile
 * pueda mostrar "Dado de baja, N pases revocados" en vez del generico
 * "Dado de baja" cuando el familiar tenia invitados en camino.
 */
public record UpdateFamilyMemberResponse(
        UUID userId,
        String fullName,
        String email,
        String phone,
        String relation,
        boolean isOwner,
        boolean canInvite,
        boolean active,
        UpdateFamilyMemberUseCase.Outcome outcome,
        int revokedInvitationsCount) {

    public static UpdateFamilyMemberResponse from(UpdateFamilyMemberUseCase.Result result) {
        var v = result.view();
        return new UpdateFamilyMemberResponse(
                v.userId(), v.fullName(), v.email(), v.phone(),
                v.relation(), v.isOwner(), v.canInvite(), v.active(),
                result.outcome(), result.revokedInvitationsCount());
    }
}
