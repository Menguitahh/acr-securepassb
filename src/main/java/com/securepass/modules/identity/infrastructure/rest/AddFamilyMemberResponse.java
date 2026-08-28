package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.AddFamilyMemberUseCase;
import com.securepass.modules.identity.domain.UserStatus;

import java.util.UUID;

/**
 * WBS-110: shape unificado del alta de familiar.
 *
 * {@code outcome} discrimina el escenario para que el mobile pueda mostrar
 * el mensaje correcto sin ramificar por combinaciones de reused/changed:
 *   CREATED_USER_AND_PERMISSION -> "Se creo la cuenta y se envio el email".
 *   ADDED_PERMISSION            -> "Ya tenia cuenta; sumado al lote".
 *   REACTIVATED_PERMISSION      -> "Reactivaste a alguien que estaba dado de baja".
 *   ALREADY_MEMBER              -> "Ya era miembro del lote".
 */
public record AddFamilyMemberResponse(
        UUID userId,
        String fullName,
        String email,
        String relation,
        boolean canInvite,
        boolean active,
        boolean reused,
        boolean changed,
        AddFamilyMemberUseCase.Outcome outcome,
        UserStatus userStatus) {

    public static AddFamilyMemberResponse from(AddFamilyMemberUseCase.Result r) {
        return new AddFamilyMemberResponse(
                r.userId(), r.fullName(), r.email(), r.relation(),
                r.canInvite(), r.active(),
                r.reused(), r.changed(),
                r.outcome(), r.userStatus());
    }
}
