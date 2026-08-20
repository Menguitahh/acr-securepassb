package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.AccessDecision;
import com.securepass.modules.access.domain.AccessLog;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.Direction;
import com.securepass.modules.access.domain.SubjectType;
import com.securepass.modules.invitation.application.InvitationRepository;
import com.securepass.modules.invitation.domain.Invitation;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class RecordAccessAttemptUseCase {

    static final Duration CLOCK_SKEW_THRESHOLD = Duration.ofMinutes(5);

    private final ConsumeAccessUseCase consumeUseCase;
    private final ValidateAccessUseCase validateUseCase;
    private final InvitationRepository invitations;
    private final LotStatusQuery lots;
    private final AccessLogRepository logs;

    public RecordAccessAttemptUseCase(
            ConsumeAccessUseCase consumeUseCase,
            ValidateAccessUseCase validateUseCase,
            InvitationRepository invitations,
            LotStatusQuery lots,
            AccessLogRepository logs) {
        this.consumeUseCase = consumeUseCase;
        this.validateUseCase = validateUseCase;
        this.invitations = invitations;
        this.lots = lots;
        this.logs = logs;
    }

    public Result record(Command cmd) {
        if (cmd == null) throw new IllegalArgumentException("cmd requerido");

        Optional<AccessLog> existing = logs.findByClientEventId(cmd.clientEventId());
        if (existing.isPresent()) return reconstruct(existing.get());

        AccessDecision decision = decide(cmd);
        // NEEDS_ENROLLMENT no es un intento de acceso completado: no se persiste.
        // El guardia continua con /access/enroll (clientEventId nuevo).
        if (decision instanceof AccessDecision.NeedsEnrollment) {
            return new Result(decision, null, invitationFor(decision).orElse(null));
        }
        AccessLog toSave = buildLog(cmd, decision);
        AccessLog persisted = logs.saveIdempotent(toSave);
        if (persisted.id().equals(toSave.id())) {
            return new Result(decision, persisted, invitationFor(decision).orElse(null));
        }
        return reconstruct(persisted);
    }

    private Result reconstruct(AccessLog log) {
        if (!log.allowed()) {
            return new Result(AccessDecision.Denied.of(log.denialReason()), log, null);
        }
        Invitation inv = invitations.findById(log.invitationId()).orElse(null);
        String lotCode = lots.find(log.lotId()).map(LotSnapshot::code).orElse("");
        AccessDecision.Allowed allowed = new AccessDecision.Allowed(
                log.invitationId(),
                log.tokenJti(),
                log.subjectName(),
                lotCode,
                log.companions());
        return new Result(allowed, log, inv);
    }

    private AccessDecision decide(Command cmd) {
        boolean checkEnrollment = !cmd.syncedOffline();
        AccessDecision decision = cmd.direction() == Direction.IN
                ? consumeUseCase.consume(cmd.token(), cmd.capturedAt(), cmd.clientEventId().toString(), checkEnrollment)
                : validateUseCase.validate(cmd.token(), cmd.capturedAt(), Direction.OUT, checkEnrollment);
        if (cmd.syncedOffline()
                && decision instanceof AccessDecision.Denied denied
                && denied.reason() == DenialReason.ALREADY_USED) {
            return AccessDecision.Denied.of(DenialReason.REPLAY);
        }
        return decision;
    }

    private Optional<Invitation> invitationFor(AccessDecision decision) {
        if (decision instanceof AccessDecision.Allowed allowed) {
            return invitations.findById(allowed.invitationId());
        }
        if (decision instanceof AccessDecision.NeedsEnrollment needs) {
            return invitations.findById(needs.invitationId());
        }
        return Optional.empty();
    }

    private AccessLog buildLog(Command cmd, AccessDecision decision) {
        boolean allowed = decision instanceof AccessDecision.Allowed;
        DenialReason reason = decision instanceof AccessDecision.Denied denied ? denied.reason() : null;
        Optional<Invitation> inv = invitationFor(decision);
        UUID tokenJti = inv.map(Invitation::jti).orElse(null);
        UUID invitationId = inv.map(Invitation::id).orElse(null);
        UUID lotId = inv.map(Invitation::lotId).orElse(null);
        String subjectName = allowed && inv.isPresent() ? inv.get().guestName() : null;
        String subjectDocument = allowed && inv.isPresent() ? inv.get().guestDocument() : null;
        boolean clockSkew = cmd.syncedOffline()
                && Duration.between(cmd.capturedAt(), Instant.now()).abs().compareTo(CLOCK_SKEW_THRESHOLD) > 0;

        return new AccessLog(
                UUID.randomUUID(),
                cmd.neighborhoodId(),
                cmd.gateId(),
                cmd.guardId(),
                invitationId,
                lotId,
                cmd.direction(),
                SubjectType.GUEST,
                allowed,
                reason,
                subjectName,
                subjectDocument,
                cmd.companions(),
                tokenJti,
                cmd.capturedAt(),
                cmd.syncedOffline(),
                clockSkew,
                cmd.clientEventId());
    }

    public record Command(
            UUID neighborhoodId,
            UUID gateId,
            UUID guardId,
            String token,
            Direction direction,
            int companions,
            Instant capturedAt,
            UUID clientEventId,
            boolean syncedOffline
    ) {
        public Command {
            if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
            if (gateId == null) throw new IllegalArgumentException("gateId requerido");
            if (guardId == null) throw new IllegalArgumentException("guardId requerido");
            if (token == null || token.isBlank()) throw new IllegalArgumentException("token requerido");
            if (direction == null) throw new IllegalArgumentException("direction requerido");
            if (companions < 0) throw new IllegalArgumentException("companions >= 0");
            if (capturedAt == null) throw new IllegalArgumentException("capturedAt requerido");
            if (clientEventId == null) throw new IllegalArgumentException("clientEventId requerido");
        }
    }

    public record Result(AccessDecision decision, AccessLog log, Invitation invitation) {}
}
