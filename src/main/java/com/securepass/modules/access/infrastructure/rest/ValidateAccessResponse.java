package com.securepass.modules.access.infrastructure.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.securepass.modules.access.application.RecordAccessAttemptUseCase;
import com.securepass.modules.access.domain.AccessDecision;
import com.securepass.modules.invitation.domain.Invitation;

import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValidateAccessResponse(
        String result,
        UUID logId,
        String reason,
        UUID invitationId,
        GuestDetail guest,
        LotDetail lot,
        InvitationDetail invitation,
        WorkerDetail worker
) {

    public record GuestDetail(String name, String document, int companions) {}

    public record LotDetail(String code) {}

    public record InvitationDetail(String type, int usedCount, int maxUses, int remaining) {}

    /**
     * WBS-117: shape ALLOWED del trabajador. {@code lots} tiene los lotes en
     * franja horaria; si {@code lots.size()==1} la garita entra directo, si >1
     * el guardia elige (US-67, spec §L).
     */
    public record WorkerDetail(UUID userId, String name, List<LotChoice> lots) {}

    public record LotChoice(UUID lotId, String code, String service) {}

    public static ValidateAccessResponse from(RecordAccessAttemptUseCase.Result result) {
        UUID logId = result.log() == null ? null : result.log().id();
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
                    "ALLOWED", logId, null, null, guest, new LotDetail(allowed.lotCode()), invDetail, null);
        }
        if (result.decision() instanceof AccessDecision.AllowedWorker w) {
            List<LotChoice> lots = w.lots().stream()
                    .map(o -> new LotChoice(o.lotId(), o.lotCode(), o.service()))
                    .toList();
            return new ValidateAccessResponse(
                    "ALLOWED_WORKER", logId, null, null, null, null, null,
                    new WorkerDetail(w.userId(), w.workerName(), lots));
        }
        if (result.decision() instanceof AccessDecision.NeedsEnrollment needs) {
            return new ValidateAccessResponse(
                    "NEEDS_ENROLLMENT", null, null, needs.invitationId(),
                    null, new LotDetail(needs.lotCode()), null, null);
        }
        AccessDecision.Denied denied = (AccessDecision.Denied) result.decision();
        return new ValidateAccessResponse(
                "DENIED", logId, denied.reason().name(), null, null, null, null, null);
    }
}
