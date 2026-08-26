package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.RefreshTokenUseCase;

public record RefreshResponse(String accessToken, String refreshToken, long expiresIn) {
    public static RefreshResponse from(RefreshTokenUseCase.Success s) {
        return new RefreshResponse(s.accessToken(), s.refreshToken(), s.expiresIn());
    }
}
