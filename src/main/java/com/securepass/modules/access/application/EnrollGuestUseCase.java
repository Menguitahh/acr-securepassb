package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.AccessLog;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.Direction;
import com.securepass.modules.access.domain.GuestEnrollment;
import com.securepass.modules.access.domain.SubjectType;
import com.securepass.modules.invitation.application.InvitationRepository;
import com.securepass.modules.invitation.application.ParsedQrToken;
import com.securepass.modules.invitation.application.QrTokenCodec;
import com.securepass.modules.invitation.application.QrTokenPayload;
import com.securepass.modules.invitation.application.SigningKeyRepository;
import com.securepass.modules.invitation.application.WorkerTokenRepository;
import com.securepass.modules.invitation.application.WorkerValidationQuery;
import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.modules.invitation.domain.InvitationStatus;
import com.securepass.modules.invitation.domain.SigningKey;
import com.securepass.shared.crypto.Ed25519Verifier;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.exception.StateConflictException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class EnrollGuestUseCase {

    // ponytail: duplicado de RecordAccessAttemptUseCase.CLOCK_SKEW_THRESHOLD.
    // Extraer a shared/time cuando aparezca un tercer caller. Con dos el costo
    // de una constante compartida (y su import) supera el ahorro.
    static final Duration CLOCK_SKEW_THRESHOLD = Duration.ofMinutes(5);

    private final QrTokenCodec codec;
    private final SigningKeyRepository signingKeys;
    private final InvitationRepository invitations;
    private final GuestEnrollmentRepository enrollments;
    private final AccessLogRepository logs;
    private final StayLifecycle stayLifecycle;
    private final WorkerTokenRepository workerTokens;
    private final WorkerValidationQuery workerQuery;

    public EnrollGuestUseCase(
            QrTokenCodec codec,
            SigningKeyRepository signingKeys,
            InvitationRepository invitations,
            GuestEnrollmentRepository enrollments,
            AccessLogRepository logs,
            StayLifecycle stayLifecycle,
            WorkerTokenRepository workerTokens,
            WorkerValidationQuery workerQuery) {
        this.codec = codec;
        this.signingKeys = signingKeys;
        this.invitations = invitations;
        this.enrollments = enrollments;
        this.logs = logs;
        this.stayLifecycle = stayLifecycle;
        this.workerTokens = workerTokens;
        this.workerQuery = workerQuery;
    }

    public EnrollmentResult enroll(Command cmd) {
        if (cmd == null) throw new IllegalArgumentException("cmd requerido");
        // ponytail: cuando WBS-27 este mergeado, requerir aca turno abierto
        // del guardId en el gateId, igual que el ingreso manual (WBS-26).

        Optional<AccessLog> existing = logs.findByClientEventId(cmd.clientEventId());
        if (existing.isPresent()) {
            if (!cmd.documentNumber().equals(existing.get().subjectDocument())) {
                throw new StateConflictException(
                        "clientEventId ya usado con otro documento; generar uno nuevo");
            }
            return reconstruct(existing.get(), cmd.neighborhoodId());
        }

        Optional<ParsedQrToken> parsedOpt = codec.parse(cmd.token());
        if (parsedOpt.isEmpty()) return denyUnknown(cmd, DenialReason.INVALID_SIGNATURE);
        ParsedQrToken parsed = parsedOpt.get();

        Optional<SigningKey> keyOpt = signingKeys.findByKid(
                parsed.payload().neighborhoodId(), parsed.payload().kid());
        if (keyOpt.isEmpty()) return denyUnknown(cmd, DenialReason.INVALID_SIGNATURE);
        if (!Ed25519Verifier.verify(keyOpt.get().publicKey(), parsed.payloadBytes(), parsed.signature())) {
            return denyUnknown(cmd, DenialReason.INVALID_SIGNATURE);
        }

        // WBS-122: mismo endpoint, ruteo por subType — el trabajador tambien
        // enrola por (barrio, documento) y en su primer ingreso.
        if (QrTokenPayload.SUBTYPE_WORKER.equals(parsed.payload().subType())) {
            return enrollWorker(cmd, parsed);
        }
        return enrollGuest(cmd, parsed);
    }

    private EnrollmentResult enrollGuest(Command cmd, ParsedQrToken parsed) {
        Invitation inv = invitations.findById(parsed.payload().invitationId())
                .orElseThrow(() -> new ResourceNotFoundException("invitation", parsed.payload().invitationId()));
        if (!inv.jti().equals(parsed.payload().jti())) {
            return denyGuest(cmd, inv, DenialReason.INVALID_SIGNATURE);
        }
        if (!inv.neighborhoodId().equals(cmd.neighborhoodId())) {
            throw new ResourceNotFoundException("invitation", inv.id());
        }
        if (inv.status() != InvitationStatus.ACTIVE) {
            return denyGuest(cmd, inv, DenialReason.REVOKED);
        }
        if (!cmd.documentNumber().equals(inv.guestDocument())) {
            // BR-12: no coincide -> DOCUMENT_MISMATCH y persistencia obligatoria.
            return denyGuest(cmd, inv, DenialReason.DOCUMENT_MISMATCH);
        }

        GuestEnrollment enrollment = enrollments.saveIfAbsent(new GuestEnrollment(
                UUID.randomUUID(), cmd.neighborhoodId(), inv.id(), cmd.documentNumber(),
                Instant.now(), cmd.guardId(), cmd.gateId()));
        AccessLog log = logs.saveIdempotent(new AccessLog(
                UUID.randomUUID(), cmd.neighborhoodId(), cmd.gateId(), cmd.guardId(),
                inv.id(), inv.lotId(),
                Direction.IN, SubjectType.GUEST, true, null,
                inv.guestName(), cmd.documentNumber(), inv.companions(),
                inv.jti(), cmd.capturedAt(), cmd.syncedOffline(), clockSkew(cmd), cmd.clientEventId()));
        stayLifecycle.onAllowed(log, inv); // BR-14
        return new EnrollmentResult.Enrolled(enrollment, log, inv.guestName());
    }

    /**
     * WBS-122: enrolamiento por la via del trabajador. Verifica firma + JTI
     * activo (misma logica que evaluateWorker) y compara el DNI cargado
     * contra el del user detras del token. Match -> guarda enrolamiento con
     * {@code invitation_id=null} y loguea un ingreso SERVICE. Mismatch ->
     * DOCUMENT_MISMATCH persistido: es exactamente el caso "le prestaron el
     * QR a otro" que el enrolamiento viene a detectar.
     *
     * <p>{@code lot_id} en el log queda NULL de forma deliberada. El
     * enrolamiento ocurre una sola vez por persona y no es la via normal de
     * ingreso: computar aca la franja para elegir lote duplicaria
     * evaluateWorker. El proximo scan pasa por validate y resuelve el lote
     * correcto para ese momento.
     */
    private EnrollmentResult enrollWorker(Command cmd, ParsedQrToken parsed) {
        QrTokenPayload p = parsed.payload();
        if (!cmd.neighborhoodId().equals(p.neighborhoodId())) {
            // Aislamiento por barrio: un token de otro barrio en esta garita
            // se rechaza como invitacion inexistente.
            throw new ResourceNotFoundException("worker", p.sub());
        }
        Optional<WorkerTokenRepository.WorkerTokenRow> active = workerTokens.findActive(p.sub());
        if (active.isEmpty() || !active.get().jti().equals(p.jti())) {
            return denyWorker(cmd, p.sub(), p.jti(), DenialReason.REVOKED);
        }
        Optional<WorkerValidationQuery.WorkerContext> ctxOpt =
                workerQuery.loadContext(p.sub(), p.neighborhoodId());
        if (ctxOpt.isEmpty() || ctxOpt.get().lots().isEmpty()) {
            return denyWorker(cmd, p.sub(), p.jti(), DenialReason.REVOKED);
        }
        WorkerValidationQuery.WorkerContext ctx = ctxOpt.get();
        if (!cmd.documentNumber().equals(ctx.workerDocument())) {
            // BR-12 en la via del trabajador: el guardia cargo un DNI que no
            // corresponde al user del token. Persistir el intento.
            return denyWorker(cmd, p.sub(), p.jti(), DenialReason.DOCUMENT_MISMATCH);
        }
        GuestEnrollment enrollment = enrollments.saveIfAbsent(new GuestEnrollment(
                UUID.randomUUID(), cmd.neighborhoodId(), null, cmd.documentNumber(),
                Instant.now(), cmd.guardId(), cmd.gateId()));
        AccessLog log = logs.saveIdempotent(new AccessLog(
                UUID.randomUUID(), cmd.neighborhoodId(), cmd.gateId(), cmd.guardId(),
                null, null,
                Direction.IN, SubjectType.SERVICE, true, null,
                ctx.workerName(), cmd.documentNumber(), 0,
                p.jti(), cmd.capturedAt(), cmd.syncedOffline(), clockSkew(cmd), cmd.clientEventId()));
        // El trabajador no abre stay: no dispara stayLifecycle.
        return new EnrollmentResult.Enrolled(enrollment, log, ctx.workerName());
    }

    private EnrollmentResult denyUnknown(Command cmd, DenialReason reason) {
        AccessLog log = logs.saveIdempotent(new AccessLog(
                UUID.randomUUID(), cmd.neighborhoodId(), cmd.gateId(), cmd.guardId(),
                null, null,
                Direction.IN, SubjectType.GUEST, false, reason,
                null, cmd.documentNumber(), 0,
                null, cmd.capturedAt(), cmd.syncedOffline(), clockSkew(cmd), cmd.clientEventId()));
        return new EnrollmentResult.Denied(reason, log);
    }

    private EnrollmentResult denyGuest(Command cmd, Invitation inv, DenialReason reason) {
        AccessLog log = logs.saveIdempotent(new AccessLog(
                UUID.randomUUID(), cmd.neighborhoodId(), cmd.gateId(), cmd.guardId(),
                inv.id(), inv.lotId(),
                Direction.IN, SubjectType.GUEST, false, reason,
                null, cmd.documentNumber(), 0,
                inv.jti(), cmd.capturedAt(), cmd.syncedOffline(), clockSkew(cmd), cmd.clientEventId()));
        return new EnrollmentResult.Denied(reason, log);
    }

    private EnrollmentResult denyWorker(Command cmd, UUID userId, UUID jti, DenialReason reason) {
        AccessLog log = logs.saveIdempotent(new AccessLog(
                UUID.randomUUID(), cmd.neighborhoodId(), cmd.gateId(), cmd.guardId(),
                null, null,
                Direction.IN, SubjectType.SERVICE, false, reason,
                null, cmd.documentNumber(), 0,
                jti, cmd.capturedAt(), cmd.syncedOffline(), clockSkew(cmd), cmd.clientEventId()));
        return new EnrollmentResult.Denied(reason, log);
    }

    private static boolean clockSkew(Command cmd) {
        return cmd.syncedOffline()
                && Duration.between(cmd.capturedAt(), Instant.now()).abs().compareTo(CLOCK_SKEW_THRESHOLD) > 0;
    }

    private EnrollmentResult reconstruct(AccessLog log, UUID neighborhoodId) {
        if (!log.allowed()) return new EnrollmentResult.Denied(log.denialReason(), log);
        GuestEnrollment enrollment = enrollments.findByPerson(neighborhoodId, log.subjectDocument())
                .orElseThrow(() -> new IllegalStateException(
                        "log ALLOWED sin enrolamiento para documento " + log.subjectDocument()));
        return new EnrollmentResult.Enrolled(enrollment, log, log.subjectName());
    }

    public record Command(
            UUID neighborhoodId,
            UUID gateId,
            UUID guardId,
            String token,
            String documentNumber,
            UUID clientEventId,
            Instant capturedAt,
            boolean syncedOffline
    ) {
        public Command {
            if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
            if (gateId == null) throw new IllegalArgumentException("gateId requerido");
            if (guardId == null) throw new IllegalArgumentException("guardId requerido");
            if (token == null || token.isBlank()) throw new IllegalArgumentException("token requerido");
            if (documentNumber == null || documentNumber.isBlank()) {
                throw new IllegalArgumentException("documentNumber requerido");
            }
            if (clientEventId == null) throw new IllegalArgumentException("clientEventId requerido");
            if (capturedAt == null) throw new IllegalArgumentException("capturedAt requerido");
        }
    }
}
