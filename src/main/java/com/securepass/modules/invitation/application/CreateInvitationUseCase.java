package com.securepass.modules.invitation.application;

import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.modules.invitation.domain.InvitationStatus;
import com.securepass.modules.invitation.domain.InvitationType;
import com.securepass.modules.invitation.domain.SigningKey;
import com.securepass.shared.exception.BusinessRuleViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Service
public class CreateInvitationUseCase {

    private final InvitationRepository invitations;
    private final FamilyMemberQuery family;
    private final SigningKeyRepository signingKeys;
    private final QrTokenIssuer qrTokenIssuer;
    private final String shareUrlBase;

    public CreateInvitationUseCase(
            InvitationRepository invitations,
            FamilyMemberQuery family,
            SigningKeyRepository signingKeys,
            QrTokenIssuer qrTokenIssuer,
            @Value("${securepass.share-url-base:https://securepass.app/i}") String shareUrlBase) {
        this.invitations = invitations;
        this.family = family;
        this.signingKeys = signingKeys;
        this.qrTokenIssuer = qrTokenIssuer;
        this.shareUrlBase = shareUrlBase;
    }

    public IssuedInvitation create(UUID actorUserId, UUID actorNeighborhoodId, Command cmd) {
        if (actorUserId == null) throw new IllegalArgumentException("actorUserId requerido");
        if (actorNeighborhoodId == null) throw new IllegalArgumentException("actorNeighborhoodId requerido");
        if (cmd == null) throw new IllegalArgumentException("cmd requerido");

        FamilyMembership fm = family.find(actorUserId, cmd.lotId())
                .orElseThrow(() -> new BusinessRuleViolationException(
                        "BR-01", "usuario no pertenece al lote"));
        if (!fm.active() || !fm.canInvite()) {
            throw new BusinessRuleViolationException("BR-01",
                    "usuario sin permiso para invitar en este lote");
        }

        SigningKey key = signingKeys.findActive(actorNeighborhoodId)
                .orElseThrow(() -> new IllegalStateException(
                        "no hay signing key activa para el barrio " + actorNeighborhoodId));

        UUID id = UUID.randomUUID();
        UUID jti = UUID.randomUUID();
        Instant now = Instant.now();
        Invitation invitation;
        try {
            invitation = new Invitation(
                    id, actorNeighborhoodId, cmd.lotId(), actorUserId,
                    cmd.type(), InvitationStatus.ACTIVE,
                    cmd.guestName(), cmd.guestDocument(), cmd.companions(), cmd.notes(),
                    cmd.validFrom(), cmd.validUntil(),
                    cmd.weekdays(), cmd.dailyFrom(), cmd.dailyUntil(),
                    cmd.maxUses(), 0, key.kid(), jti, false, now);
        } catch (IllegalArgumentException ex) {
            String message = ex.getMessage() == null ? "invitacion invalida" : ex.getMessage();
            String rule = message.startsWith("BR-") ? message.substring(0, 5) : "invitation-invalid";
            throw new BusinessRuleViolationException(rule, message);
        }

        invitations.save(invitation);

        String qrToken = qrTokenIssuer.issue(new QrTokenIssuer.IssueCommand(
                actorNeighborhoodId, cmd.lotId(), invitation.id(), invitation.jti(),
                invitation.type(), invitation.validFrom(), invitation.validUntil(),
                invitation.maxUses(),
                invitation.weekdays(), invitation.dailyFrom(), invitation.dailyUntil()));

        String shareUrl = shareUrlBase + "/" + shortCode(invitation.id());
        return new IssuedInvitation(invitation, qrToken, shareUrl);
    }

    private static String shortCode(UUID id) {
        return id.toString().replace("-", "").substring(0, 8);
    }

    public record Command(
            UUID lotId,
            InvitationType type,
            String guestName,
            String guestDocument,
            int companions,
            String notes,
            Instant validFrom,
            Instant validUntil,
            List<Integer> weekdays,
            LocalTime dailyFrom,
            LocalTime dailyUntil,
            int maxUses
    ) {}
}
