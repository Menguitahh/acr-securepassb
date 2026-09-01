package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.LoginUseCase;
import com.securepass.modules.identity.application.LotSummary;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

public record LoginResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,
        UserPayload user
) {
    public static LoginResponse from(LoginUseCase.Success s) {
        return new LoginResponse(
                s.accessToken(),
                s.refreshToken(),
                s.expiresIn(),
                new UserPayload(
                        s.userId(),
                        s.fullName(),
                        s.neighborhoodId(),
                        s.neighborhoodTimezone(),
                        s.roles().stream().map(Enum::name).toList(),
                        s.lots().stream().map(LotPayload::from).toList()));
    }

    public record UserPayload(
            UUID id,
            String fullName,
            UUID neighborhoodId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "IANA timezone del barrio, ej. America/Argentina/Mendoza. Fuente unica para evaluar ScheduleWindow client-side.")
            String neighborhoodTimezone,
            List<String> roles,
            List<LotPayload> lots
    ) {}

    public record LotPayload(UUID id, String code, boolean canInvite, boolean isOwner) {
        static LotPayload from(LotSummary l) {
            return new LotPayload(l.id(), l.code(), l.canInvite(), l.isOwner());
        }
    }
}
