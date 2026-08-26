package com.securepass.modules.invitation.application;

import com.securepass.modules.invitation.domain.Invitation;

public record IssuedInvitation(Invitation invitation, String qrToken, String shareUrl) {

    public IssuedInvitation {
        if (invitation == null) throw new IllegalArgumentException("invitation requerida");
        if (qrToken == null || qrToken.isBlank()) throw new IllegalArgumentException("qrToken requerido");
        if (shareUrl == null || shareUrl.isBlank()) throw new IllegalArgumentException("shareUrl requerido");
    }
}
