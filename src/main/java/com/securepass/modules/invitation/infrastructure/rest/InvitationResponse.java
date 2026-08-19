package com.securepass.modules.invitation.infrastructure.rest;

import com.securepass.modules.invitation.application.IssuedInvitation;
import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.modules.invitation.domain.InvitationStatus;
import com.securepass.modules.invitation.domain.InvitationType;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record InvitationResponse(
        UUID id,
        UUID lotId,
        UUID neighborhoodId,
        String neighborhoodName,
        InvitationType type,
        InvitationStatus status,
        String guestName,
        String guestDocument,
        int companions,
        String notes,
        Instant validFrom,
        Instant validUntil,
        List<Integer> weekdays,
        LocalTime dailyFrom,
        LocalTime dailyUntil,
        int maxUses,
        int usedCount,
        String qrToken,
        String shareUrl
) {

    public static InvitationResponse from(Invitation inv, String neighborhoodName, String qrToken, String shareUrl) {
        return new InvitationResponse(
                inv.id(), inv.lotId(), inv.neighborhoodId(), neighborhoodName,
                inv.type(), inv.status(),
                inv.guestName(), inv.guestDocument(), inv.companions(), inv.notes(),
                inv.validFrom(), inv.validUntil(),
                inv.weekdays(), inv.dailyFrom(), inv.dailyUntil(),
                inv.maxUses(), inv.usedCount(), qrToken, shareUrl);
    }

    public static InvitationResponse from(IssuedInvitation issued, String neighborhoodName) {
        return from(issued.invitation(), neighborhoodName, issued.qrToken(), issued.shareUrl());
    }

    public static InvitationResponse withoutQr(Invitation inv, String neighborhoodName) {
        return from(inv, neighborhoodName, null, null);
    }
}
