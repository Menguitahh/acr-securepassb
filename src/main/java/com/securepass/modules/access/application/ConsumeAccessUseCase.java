package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.AccessDecision;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.invitation.application.InvitationRepository;
import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.modules.invitation.domain.InvitationStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class ConsumeAccessUseCase {

    private static final Duration MIN_TTL = Duration.ofMinutes(5);
    private static final Duration REPLAY_TTL_BUFFER = Duration.ofSeconds(300);

    private final ValidateAccessUseCase validator;
    private final InvitationRepository invitations;
    private final AntiReplayStore antiReplay;

    public ConsumeAccessUseCase(
            ValidateAccessUseCase validator,
            InvitationRepository invitations,
            AntiReplayStore antiReplay) {
        this.validator = validator;
        this.invitations = invitations;
        this.antiReplay = antiReplay;
    }

    @Transactional
    public AccessDecision consume(UUID neighborhoodId, String token, Instant capturedAt, String clientEventId,
                                  boolean checkEnrollment, UUID lotId) {
        if (clientEventId == null || clientEventId.isBlank()) {
            throw new IllegalArgumentException("clientEventId requerido");
        }
        // #217: validate chequea lotId antes del lock: un lote invalido no consume.
        AccessDecision pre = validator.validate(
                neighborhoodId, token, capturedAt, com.securepass.modules.access.domain.Direction.IN, checkEnrollment,
                lotId);
        if (!(pre instanceof AccessDecision.Allowed allowed)) return pre;

        Invitation locked = invitations.findByIdForUpdate(allowed.invitationId()).orElse(null);
        if (locked == null) return AccessDecision.Denied.of(DenialReason.UNKNOWN);
        if (locked.status() == InvitationStatus.REVOKED) {
            return AccessDecision.Denied.of(DenialReason.REVOKED, locked.id());
        }
        if (locked.usedCount() >= locked.maxUses() || locked.status() == InvitationStatus.USED) {
            return AccessDecision.Denied.of(DenialReason.ALREADY_USED, locked.id());
        }

        int useSeq = locked.usedCount() + 1;
        Duration ttl = replayTtl(locked.validUntil());
        AntiReplayStore.ClaimResult claim = antiReplay.claim(locked.jti(), useSeq, clientEventId, ttl);

        if (claim == AntiReplayStore.ClaimResult.REPLAY) {
            return AccessDecision.Denied.of(DenialReason.REPLAY, locked.id());
        }
        if (claim == AntiReplayStore.ClaimResult.IDEMPOTENT) {
            return allowed;
        }

        Invitation consumed = locked.registerEntry();
        invitations.persistConsumption(consumed);
        return new AccessDecision.Allowed(
                consumed.id(), consumed.jti(), consumed.guestName(),
                allowed.lotCode(), consumed.companions());
    }

    private static Duration replayTtl(Instant validUntil) {
        Duration remaining = Duration.between(Instant.now(), validUntil).plus(REPLAY_TTL_BUFFER);
        return remaining.compareTo(MIN_TTL) < 0 ? MIN_TTL : remaining;
    }
}
