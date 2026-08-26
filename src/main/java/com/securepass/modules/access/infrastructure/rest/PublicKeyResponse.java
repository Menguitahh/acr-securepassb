package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.invitation.application.PublicKeyView;

import java.util.Base64;

public record PublicKeyResponse(String kid, String publicKey) {
    public static PublicKeyResponse from(PublicKeyView view) {
        return new PublicKeyResponse(
                view.kid(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(view.publicKey()));
    }
}
