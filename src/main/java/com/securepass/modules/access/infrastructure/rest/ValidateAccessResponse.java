package com.securepass.modules.access.infrastructure.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.securepass.modules.access.application.RecordAccessAttemptUseCase;
import com.securepass.modules.access.domain.AccessDecision;
import com.securepass.modules.invitation.domain.Invitation;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValidateAccessResponse(
        String result,
        UUID logId,
        String reason,
        GuestDetail guest,
        LotDetail lot,
        InvitationDetail invitation
) {

    public record GuestDetail(String name, String document, int companions) {}

    public record LotDetail(String code) {}

    public record InvitationDetail(String type, int usedCount, int maxUses, int remaining) {}

    public static ValidateAccessResponse from(RecordAccessAttemptUseCase.Result result) {
        UUID logId = result.log().id();
        if (result.decision() instanceof AccessDecision.Allowed allowed) {
            Invitation inv = result.invitation();
            InvitationDetail invDetail = inv == null ? null : new InvitationDetail(
                    inv.type().name(), inv.usedCount(), inv.maxUses(),
                    Math.max(0, inv.maxUses() - inv.usedCount()));
            GuestDetail guest = new GuestDetail(
                    allowed.guestName(),
                    inv == null ? null : inv.guestDocument(),
                    allowed.companions());
            return new ValidateAccessResponse(
                    "ALLOWED", logId, null, guest, new LotDetail(allowed.lotCode()), invDetail);
        }
        AccessDecision.Denied denied = (AccessDecision.Denied) result.decision();
        return new ValidateAccessResponse(
                "DENIED", logId, denied.reason().name(), null, null, null);
    }
}
