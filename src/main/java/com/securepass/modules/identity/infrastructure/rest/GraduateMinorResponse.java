package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.GraduateMinorUseCase;

import java.util.UUID;

/**
 * WBS-111: response de graduate. Extiende {@link AddFamilyMemberResponse}
 * con {@code graduatedFromMinorId} para que el mobile pueda actualizar
 * su lista de menores (removiendo esa fila) ademas de la de familiares.
 */
public record GraduateMinorResponse(
        UUID graduatedFromMinorId,
        AddFamilyMemberResponse member) {

    public static GraduateMinorResponse from(GraduateMinorUseCase.Result r) {
        return new GraduateMinorResponse(
                r.graduatedFromMinorId(),
                AddFamilyMemberResponse.from(r.added()));
    }
}
