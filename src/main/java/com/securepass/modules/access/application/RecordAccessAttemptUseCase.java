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
    private final StayLifecycle stayLifecycle;
    private final com.securepass.shared.metrics.AccessMetrics metrics;

    public RecordAccessAttemptUseCase(
            ConsumeAccessUseCase consumeUseCase,
            ValidateAccessUseCase validateUseCase,
            InvitationRepository invitations,
            LotStatusQuery lots,
            AccessLogRepository logs,
            StayLifecycle stayLifecycle,
            com.securepass.shared.metrics.AccessMetrics metrics) {
        this.consumeUseCase = consumeUseCase;
        this.validateUseCase = validateUseCase;
        this.invitations = invitations;
        this.lots = lots;
        this.logs = logs;
        this.stayLifecycle = stayLifecycle;
        this.metrics = metrics;
    }

    public Result record(Command cmd) {
        if (cmd == null) throw new IllegalArgumentException("cmd requerido");

        Optional<AccessLog> existing = logs.findByClientEventId(cmd.clientEventId());
        if (existing.isPresent()) return reconstruct(existing.get());

        AccessDecision decision = decide(cmd);
        metrics.recordResult(metricLabel(decision), cmd.syncedOffline() ? "sync" : "validate");
        // NEEDS_ENROLLMENT no es un intento de acceso completado: no se persiste.
        // El guardia continua con /access/enroll (clientEventId nuevo).
        if (decision instanceof AccessDecision.NeedsEnrollment) {
            return new Result(decision, null, invitationFor(decision).orElse(null));
        }
        AccessLog toSave = buildLog(cmd, decision);
        AccessLog persisted = logs.saveIdempotent(toSave);
        if (persisted.id().equals(toSave.id())) {
            Invitation inv = invitationFor(decision).orElse(null);
            stayLifecycle.onAllowed(persisted, inv); // BR-14
            return new Result(decision, persisted, inv);
        }
        return reconstruct(persisted);
    }

    private Result reconstruct(AccessLog log) {
        if (!log.allowed()) {
            return new Result(AccessDecision.Denied.of(log.denialReason()), log, null);
        }
        // WBS-117: en un ALLOWED de worker (subject SERVICE) no hay invitationId.
        // La reconstruccion no puede recuperar la lista de lotes (validate la
        // computa online), asi que en el path idempotente devolvemos un
        // Denied.UNKNOWN — el guardia reescanea. Camino de borde: mismo
        // clientEventId reintentado sin cache local. Aceptable, la ventana
        // real es milisegundos.
        if (log.subjectType() == SubjectType.SERVICE) {
            return new Result(AccessDecision.Denied.of(DenialReason.UNKNOWN), log, null);
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
        if (decision instanceof AccessDecision.NeedsEnrollment needs && needs.invitationId() != null) {
            // WBS-122: NeedsEnrollment.WORKER no tiene invitationId.
            return invitations.findById(needs.invitationId());
        }
        return Optional.empty();
    }

    private AccessLog buildLog(Command cmd, AccessDecision decision) {
        boolean allowed = decision instanceof AccessDecision.Allowed
                || decision instanceof AccessDecision.AllowedWorker;
        DenialReason reason = decision instanceof AccessDecision.Denied denied ? denied.reason() : null;
        boolean clockSkew = cmd.syncedOffline()
                && Duration.between(cmd.capturedAt(), Instant.now()).abs().compareTo(CLOCK_SKEW_THRESHOLD) > 0;

        // WBS-117: worker (AllowedWorker) -> subject SERVICE, sin invitationId,
        // lot_id nulo si hay varios en franja (el guardia elige, WBS-119).
        if (decision instanceof AccessDecision.AllowedWorker w) {
            UUID lotId = w.lots().size() == 1 ? w.lots().get(0).lotId() : null;
            return new AccessLog(
                    UUID.randomUUID(), cmd.neighborhoodId(), cmd.gateId(), cmd.guardId(),
                    null, lotId, cmd.direction(), SubjectType.SERVICE,
                    true, null, w.workerName(), null, 0,
                    w.jti(), cmd.capturedAt(), cmd.syncedOffline(), clockSkew,
                    cmd.clientEventId());
        }

        Optional<Invitation> inv = invitationFor(decision);
        UUID tokenJti = inv.map(Invitation::jti).orElse(null);
        UUID invitationId = inv.map(Invitation::id).orElse(null);
        UUID lotId = inv.map(Invitation::lotId).orElse(null);
        String subjectName = allowed && inv.isPresent() ? inv.get().guestName() : null;
        String subjectDocument = allowed && inv.isPresent() ? inv.get().guestDocument() : null;

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

    private static String metricLabel(AccessDecision decision) {
        if (decision instanceof AccessDecision.Allowed) return "ALLOWED";
        if (decision instanceof AccessDecision.AllowedWorker) return "ALLOWED_WORKER";
        if (decision instanceof AccessDecision.NeedsEnrollment) return "NEEDS_ENROLLMENT";
        return ((AccessDecision.Denied) decision).reason().name();
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
