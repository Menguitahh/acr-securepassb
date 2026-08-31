package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.AccessDecision;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.Direction;
import com.securepass.modules.access.domain.LotStatus;
import com.securepass.modules.invitation.application.InvitationRepository;
import com.securepass.modules.invitation.application.ParsedQrToken;
import com.securepass.modules.invitation.application.QrTokenCodec;
import com.securepass.modules.invitation.application.QrTokenPayload;
import com.securepass.modules.invitation.application.SigningKeyRepository;
import com.securepass.modules.invitation.application.WorkerTokenRepository;
import com.securepass.modules.invitation.application.WorkerValidationQuery;
import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.modules.invitation.domain.InvitationStatus;
import com.securepass.modules.invitation.domain.InvitationType;
import com.securepass.modules.invitation.domain.SigningKey;
import com.securepass.shared.crypto.Ed25519Verifier;
import com.securepass.shared.schedule.ScheduleWindow;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class ValidateAccessUseCase {

    private final QrTokenCodec codec;
    private final SigningKeyRepository signingKeys;
    private final InvitationRepository invitations;
    private final LotStatusQuery lots;
    private final GuestEnrollmentRepository enrollments;
    private final WorkerTokenRepository workerTokens;
    private final WorkerValidationQuery workerQuery;

    public ValidateAccessUseCase(
            QrTokenCodec codec,
            SigningKeyRepository signingKeys,
            InvitationRepository invitations,
            LotStatusQuery lots,
            GuestEnrollmentRepository enrollments,
            WorkerTokenRepository workerTokens,
            WorkerValidationQuery workerQuery) {
        this.codec = codec;
        this.signingKeys = signingKeys;
        this.invitations = invitations;
        this.lots = lots;
        this.enrollments = enrollments;
        this.workerTokens = workerTokens;
        this.workerQuery = workerQuery;
    }

    public AccessDecision validate(String token, Instant capturedAt) {
        return validate(token, capturedAt, Direction.IN, true);
    }

    public AccessDecision validate(String token, Instant capturedAt, Direction direction) {
        return validate(token, capturedAt, direction, true);
    }

    public AccessDecision validate(String token, Instant capturedAt, Direction direction, boolean checkEnrollment) {
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

        // WBS-117: ruteo por subType. Worker no lleva invitationId, tiene su
        // propio flujo (permisos activos + franja evaluada en el barrio).
        if (QrTokenPayload.SUBTYPE_WORKER.equals(parsed.payload().subType())) {
            return evaluateWorker(parsed, capturedAt, direction, checkEnrollment);
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
        if (lot.status() != LotStatus.ACTIVE && !inv.residentPass()) {
            return AccessDecision.Denied.of(DenialReason.LOT_SUSPENDED);
        }

        if (capturedAt.isBefore(inv.validFrom())) return AccessDecision.Denied.of(DenialReason.NOT_YET_VALID);
        if (capturedAt.isAfter(inv.validUntil()) || inv.status() == InvitationStatus.EXPIRED) {
            return AccessDecision.Denied.of(DenialReason.EXPIRED);
        }
        if (inv.type() == InvitationType.RECURRING) {
            ScheduleWindow window = new ScheduleWindow(inv.weekdays(), inv.dailyFrom(), inv.dailyUntil());
            if (!window.contains(capturedAt, lot.neighborhoodTimezone())) {
                return AccessDecision.Denied.of(DenialReason.OUT_OF_SCHEDULE);
            }
        }
        if (direction == Direction.IN) {
            if (inv.usedCount() >= inv.maxUses()) return AccessDecision.Denied.of(DenialReason.ALREADY_USED);
            if (inv.status() != InvitationStatus.ACTIVE) return AccessDecision.Denied.of(DenialReason.UNKNOWN);
            // WBS-122: la verificacion es por persona, no por invitacion. Un
            // invitado ya verificado en el barrio pasa directo aunque sea su
            // primera vez en ESTA invitacion. residentPass no enrola (US-16);
            // egresos no requieren enrolamiento. En sync offline el mobile ya
            // verifico contra su cache, no repetimos server-side.
            if (checkEnrollment && !inv.residentPass()
                    && enrollments.findByPerson(inv.neighborhoodId(), inv.guestDocument()).isEmpty()) {
                return AccessDecision.NeedsEnrollment.guest(inv.id(), lot.code());
            }
        }

        return new AccessDecision.Allowed(inv.id(), inv.jti(), inv.guestName(), lot.code(), inv.companions());
    }

    /**
     * WBS-117 / US-67, US-68: worker.
     *
     * <p>Rechaza REVOKED si el JTI no coincide con el token activo del user en
     * {@code worker_tokens} — el usuario perdio todos los permisos WORKER
     * (rotacion via UpdateWorkerUseCase, PR WBS-116) o alguien fabrica un JTI
     * inventado con firma valida (imposible sin la clave privada, pero
     * el chequeo cierra el flanco).
     *
     * <p>Lista todos los permisos WORKER activos del user en el barrio del
     * payload, evalua {@link ScheduleWindow#contains} para cada uno con la
     * tz del barrio, y devuelve {@link AccessDecision.AllowedWorker} con los
     * lotes en franja. Si ninguno matchea horario: {@code OUT_OF_SCHEDULE}.
     *
     * <p>WBS-122: si pasa franja pero la persona no esta verificada en el
     * barrio, devuelve {@code NeedsEnrollment.worker(userId)}. La verificacion
     * es por persona; una vez enrolado no vuelve a pedirse aunque cambien los
     * permisos. Los egresos y los sync offline no chequean enrolamiento.
     */
    private AccessDecision evaluateWorker(ParsedQrToken parsed, Instant capturedAt,
                                          Direction direction, boolean checkEnrollment) {
        QrTokenPayload p = parsed.payload();
        Optional<WorkerTokenRepository.WorkerTokenRow> active = workerTokens.findActive(p.sub());
        if (active.isEmpty() || !active.get().jti().equals(p.jti())) {
            return AccessDecision.Denied.of(DenialReason.REVOKED);
        }
        Optional<WorkerValidationQuery.WorkerContext> ctxOpt = workerQuery.loadContext(p.sub(), p.neighborhoodId());
        if (ctxOpt.isEmpty() || ctxOpt.get().lots().isEmpty()) {
            // Invariante: worker_token activo => user tiene >=1 permiso activo
            // (UpdateWorkerUseCase revoca al llegar a 0). Si igual llega vacio,
            // fail-closed como REVOKED — nunca autorizar por omision.
            return AccessDecision.Denied.of(DenialReason.REVOKED);
        }
        WorkerValidationQuery.WorkerContext ctx = ctxOpt.get();
        List<AccessDecision.LotOption> inSlot = new ArrayList<>();
        for (WorkerValidationQuery.ActiveWorkerLot lot : ctx.lots()) {
            if (lot.schedule().contains(capturedAt, ctx.neighborhoodTimezone())) {
                inSlot.add(new AccessDecision.LotOption(lot.lotId(), lot.lotCode(), lot.service()));
            }
        }
        if (inSlot.isEmpty()) return AccessDecision.Denied.of(DenialReason.OUT_OF_SCHEDULE);
        if (direction == Direction.IN && checkEnrollment
                && enrollments.findByPerson(p.neighborhoodId(), ctx.workerDocument()).isEmpty()) {
            return AccessDecision.NeedsEnrollment.worker(p.sub());
        }
        return new AccessDecision.AllowedWorker(p.sub(), p.jti(), ctx.workerName(), inSlot);
    }
}
