package com.securepass.modules.invitation.application;

import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.modules.invitation.domain.InvitationStatus;
import com.securepass.modules.invitation.domain.InvitationType;
import com.securepass.modules.invitation.domain.SigningKey;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class IssueResidentPassesUseCase {

    private static final Instant FAR_FUTURE = Instant.parse("2099-12-31T23:59:59Z");
    private static final List<Integer> ALL_WEEKDAYS = List.of(1, 2, 3, 4, 5, 6, 7);

    private final ResidentLotsQuery residentLots;
    private final InvitationRepository invitations;
    private final SigningKeyRepository signingKeys;
    private final QrTokenIssuer qrTokenIssuer;

    public IssueResidentPassesUseCase(
            ResidentLotsQuery residentLots,
            InvitationRepository invitations,
            SigningKeyRepository signingKeys,
            QrTokenIssuer qrTokenIssuer) {
        this.residentLots = residentLots;
        this.invitations = invitations;
        this.signingKeys = signingKeys;
        this.qrTokenIssuer = qrTokenIssuer;
    }

    public List<ResidentPass> issue(UUID userId, UUID neighborhoodId) {
        if (userId == null) throw new IllegalArgumentException("userId requerido");
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");

        List<ResidentLot> lots = residentLots.listActiveByUser(userId, neighborhoodId);
        if (lots.isEmpty()) return List.of();

        SigningKey key = signingKeys.findActive(neighborhoodId)
                .orElseThrow(() -> new IllegalStateException(
                        "no hay signing key activa para el barrio " + neighborhoodId));

        return lots.stream()
                .map(lot -> toPass(userId, lot, key))
                .toList();
    }

    private ResidentPass toPass(UUID userId, ResidentLot lot, SigningKey key) {
        Invitation pass = invitations.findActiveResidentPass(userId, lot.lotId())
                .orElseGet(() -> createPass(userId, lot, key.kid()));
        String token = qrTokenIssuer.issue(new QrTokenIssuer.IssueCommand(
                pass.neighborhoodId(), pass.lotId(), pass.id(), pass.jti(),
                pass.type(), pass.validFrom(), pass.validUntil(), pass.maxUses(),
                pass.weekdays(), pass.dailyFrom(), pass.dailyUntil()));
        return new ResidentPass(lot.lotId(), lot.lotCode(), lot.neighborhoodName(), token, pass.tokenKid());
    }

    private Invitation createPass(UUID userId, ResidentLot lot, String kid) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Invitation pass = new Invitation(
                UUID.randomUUID(),
                lot.neighborhoodId(),
                lot.lotId(),
                userId,
                InvitationType.RECURRING,
                InvitationStatus.ACTIVE,
                "Residente",
                "00000000",
                0, null,
                now, FAR_FUTURE,
                ALL_WEEKDAYS, LocalTime.of(0, 0), LocalTime.of(23, 59, 59),
                1, 0, kid, UUID.randomUUID(), true, now);
        invitations.save(pass);
        return pass;
    }
}
