package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.TokenPurpose;

import java.time.Instant;
import java.util.UUID;

/**
 * Publicado por {@link IssueActivationTokenUseCase} despues de persistir el
 * token. Un listener AFTER_COMMIT + async lo levanta y dispara el email.
 * El plaintext del token viaja aca porque nunca se guarda en la base.
 */
public record ActivationTokenIssuedEvent(
        UUID userId,
        String email,
        String fullName,
        TokenPurpose purpose,
        String plaintextToken,
        Instant expiresAt,
        String invitedByName,
        String neighborhoodName
) {
    public ActivationTokenIssuedEvent {
        if (userId == null) throw new IllegalArgumentException("userId requerido");
        if (email == null || email.isBlank()) throw new IllegalArgumentException("email requerido");
        if (fullName == null || fullName.isBlank()) throw new IllegalArgumentException("fullName requerido");
        if (purpose == null) throw new IllegalArgumentException("purpose requerido");
        if (plaintextToken == null || plaintextToken.isBlank()) throw new IllegalArgumentException("plaintextToken requerido");
        if (expiresAt == null) throw new IllegalArgumentException("expiresAt requerido");
    }
}
