package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.RefreshTokenUseCase;

public record RefreshResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,
        LoginResponse.UserPayload user
) {
    public static RefreshResponse from(RefreshTokenUseCase.Success s) {
        return new RefreshResponse(
                s.accessToken(),
                s.refreshToken(),
                s.expiresIn(),
                new LoginResponse.UserPayload(
                        s.userId(),
                        s.fullName(),
                        s.neighborhoodId(),
                        s.neighborhoodTimezone(),
                        s.roles().stream().map(Enum::name).toList(),
                        s.lots().stream().map(LoginResponse.LotPayload::from).toList()));
    }
}
