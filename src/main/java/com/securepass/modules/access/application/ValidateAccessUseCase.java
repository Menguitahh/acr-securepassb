package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.AccessDecision;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.Direction;
import com.securepass.modules.access.domain.LotStatus;
import com.securepass.modules.invitation.application.InvitationRepository;
import com.securepass.modules.invitation.application.ParsedQrToken;
import com.securepass.modules.invitation.application.QrTokenCodec;
import com.securepass.modules.invitation.application.SigningKeyRepository;
import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.modules.invitation.domain.InvitationStatus;
import com.securepass.modules.invitation.domain.InvitationType;
import com.securepass.modules.invitation.domain.SigningKey;
import com.securepass.shared.crypto.Ed25519Verifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Optional;

@Service
public class ValidateAccessUseCase {

    private final QrTokenCodec codec;
    private final SigningKeyRepository signingKeys;
    private final InvitationRepository invitations;
    private final LotStatusQuery lots;

    public ValidateAccessUseCase(
            QrTokenCodec codec,
            SigningKeyRepository signingKeys,
            InvitationRepository invitations,
            LotStatusQuery lots) {
        this.codec = codec;
        this.signingKeys = signingKeys;
        this.invitations = invitations;
        this.lots = lots;
    }

    public AccessDecision validate(String token, Instant capturedAt) {
        return validate(token, capturedAt, Direction.IN);
    }

    public AccessDecision validate(String token, Instant capturedAt, Direction direction) {
        if (capturedAt == null) throw new IllegalArgumentException("capturedAt requerido");
        if (direction == null) throw new IllegalArgumentException("direction requerido");

        Optional<ParsedQrToken> parsedOpt = codec.parse(token);
        if (parsedOpt.isEmpty()) return AccessDecision.Denied.of(DenialReason.INVALID_SIGNATURE);
        ParsedQrToken parsed = parsedOpt.get();

        Optional<SigningKey> keyOpt = signingKeys.findByKid(parsed.payload().neighborhoodId(), parsed.payload().kid());
        if (keyOpt.isEmpty()) return AccessDecision.Denied.of(DenialReason.INVALID_SIGNATURE);
        if (!Ed25519Verifier.verify(keyOpt.get().publicKey(), parsed.payloadBytes(), parsed.signature())) {
            return AccessDecision.Denied.of(DenialReason.INVALID_SIGNATURE);
        }

        Optional<Invitation> invOpt = invitations.findById(parsed.payload().invitationId());
        if (invOpt.isEmpty()) return AccessDecision.Denied.of(DenialReason.UNKNOWN);
        Invitation inv = invOpt.get();

        if (!inv.jti().equals(parsed.payload().jti())) {
            return AccessDecision.Denied.of(DenialReason.INVALID_SIGNATURE);
        }
        if (inv.status() == InvitationStatus.REVOKED) return AccessDecision.Denied.of(DenialReason.REVOKED);

        Optional<LotSnapshot> lotOpt = lots.find(inv.lotId());
        if (lotOpt.isEmpty()) return AccessDecision.Denied.of(DenialReason.UNKNOWN);
        LotSnapshot lot = lotOpt.get();
        if (lot.status() != LotStatus.ACTIVE) return AccessDecision.Denied.of(DenialReason.LOT_SUSPENDED);

        if (capturedAt.isBefore(inv.validFrom())) return AccessDecision.Denied.of(DenialReason.NOT_YET_VALID);
        if (capturedAt.isAfter(inv.validUntil()) || inv.status() == InvitationStatus.EXPIRED) {
            return AccessDecision.Denied.of(DenialReason.EXPIRED);
        }
        if (inv.type() == InvitationType.RECURRING && !withinDailySlot(inv, capturedAt, lot.neighborhoodTimezone())) {
            return AccessDecision.Denied.of(DenialReason.OUT_OF_SCHEDULE);
        }
        if (direction == Direction.IN) {
            if (inv.usedCount() >= inv.maxUses()) return AccessDecision.Denied.of(DenialReason.ALREADY_USED);
            if (inv.status() != InvitationStatus.ACTIVE) return AccessDecision.Denied.of(DenialReason.UNKNOWN);
        }

        return new AccessDecision.Allowed(inv.id(), inv.jti(), inv.guestName(), lot.code(), inv.companions());
    }

    private static boolean withinDailySlot(Invitation inv, Instant capturedAt, java.time.ZoneId tz) {
        ZonedDateTime local = capturedAt.atZone(tz);
        int weekday = local.getDayOfWeek().getValue();
        if (!inv.weekdays().contains(weekday)) return false;
        LocalTime time = local.toLocalTime();
        return !time.isBefore(inv.dailyFrom()) && !time.isAfter(inv.dailyUntil());
    }
}
