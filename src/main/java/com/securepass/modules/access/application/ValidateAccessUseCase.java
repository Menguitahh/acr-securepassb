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
import com.securepass.modules.invitation.domain.SigningKey;
import com.securepass.shared.crypto.Ed25519Verifier;
import com.securepass.shared.exception.InvalidLotChoiceException;
import com.securepass.shared.schedule.ScheduleWindow;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    /**
     * {@code neighborhoodId} es el barrio del guardia (JWT), no el del token:
     * la clave se busca ahi, asi que un QR de otro barrio no verifica (#211).
     * Sin sobrecargas que lo omitan: ningun camino puede saltearse el control.
     *
     * <p>#217: {@code lotId} es el lote que eligio la garita, o null. Si no es
     * uno que este token habilita, {@link InvalidLotChoiceException} antes de
     * consumir nada.
     */
    public AccessDecision validate(UUID neighborhoodId, String token, Instant capturedAt, Direction direction,
                                   UUID lotId) {
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        if (capturedAt == null) throw new IllegalArgumentException("capturedAt requerido");
        if (direction == null) throw new IllegalArgumentException("direction requerido");

        Optional<ParsedQrToken> parsedOpt = codec.parse(token);
        if (parsedOpt.isEmpty()) return AccessDecision.Denied.of(DenialReason.INVALID_SIGNATURE);
        ParsedQrToken parsed = parsedOpt.get();

        Optional<SigningKey> keyOpt = signingKeys.findByKid(neighborhoodId, parsed.payload().kid());
        if (keyOpt.isEmpty()) return AccessDecision.Denied.of(DenialReason.INVALID_SIGNATURE);
        if (!Ed25519Verifier.verify(keyOpt.get().publicKey(), parsed.payloadBytes(), parsed.signature())) {
            return AccessDecision.Denied.of(DenialReason.INVALID_SIGNATURE);
        }

        // WBS-117: ruteo por subType. Worker no lleva invitationId, tiene su
        // propio flujo (permisos activos + franja evaluada en el barrio).
        if (QrTokenPayload.SUBTYPE_WORKER.equals(parsed.payload().subType())) {
            return evaluateWorker(neighborhoodId, parsed, capturedAt, direction, lotId);
        }

        Optional<Invitation> invOpt = invitations.findById(parsed.payload().invitationId());
        if (invOpt.isEmpty()) return AccessDecision.Denied.of(DenialReason.UNKNOWN);
        Invitation inv = invOpt.get();
        // Segunda barrera (#211): si la clave dejara pasar un token ajeno, la
        // invitacion igual tiene que ser de este barrio. Antes de leer nada mas
        // de ella: el DENIED no lleva datos de otro barrio.
        if (!inv.neighborhoodId().equals(neighborhoodId)) return AccessDecision.Denied.of(DenialReason.UNKNOWN);

        // Token viejo tras rotar el jti: sale como firma invalida y sin vinculo
        // al pase (#210). De aca en adelante los denegados llevan inv.id().
        if (!inv.jti().equals(parsed.payload().jti())) {
            return AccessDecision.Denied.of(DenialReason.INVALID_SIGNATURE);
        }
        // #217: el lote del invitado sale de la invitacion. Si coincide se
        // ignora; si la contradice es error del cliente.
        if (lotId != null && !lotId.equals(inv.lotId())) throw new InvalidLotChoiceException();
        if (inv.status() == InvitationStatus.REVOKED) return AccessDecision.Denied.of(DenialReason.REVOKED, inv.id());

        Optional<LotSnapshot> lotOpt = lots.find(inv.lotId());
        if (lotOpt.isEmpty()) return AccessDecision.Denied.of(DenialReason.UNKNOWN, inv.id());
        LotSnapshot lot = lotOpt.get();
        if (lot.status() != LotStatus.ACTIVE && !inv.residentPass()) {
            return AccessDecision.Denied.of(DenialReason.LOT_SUSPENDED, inv.id());
        }

        if (capturedAt.isBefore(inv.validFrom())) return AccessDecision.Denied.of(DenialReason.NOT_YET_VALID, inv.id());
        if (capturedAt.isAfter(inv.validUntil()) || inv.status() == InvitationStatus.EXPIRED) {
            return AccessDecision.Denied.of(DenialReason.EXPIRED, inv.id());
        }
        // WBS-127: sacada la rama RECURRING. El unico consumidor de
        // ScheduleWindow ahora es evaluateWorker (WBS-117).
        if (direction == Direction.IN) {
            if (inv.usedCount() >= inv.maxUses()) return AccessDecision.Denied.of(DenialReason.ALREADY_USED, inv.id());
            if (inv.status() != InvitationStatus.ACTIVE) return AccessDecision.Denied.of(DenialReason.UNKNOWN, inv.id());
            // WBS-122: la verificacion es por persona, no por invitacion. Un
            // invitado ya verificado en el barrio pasa directo aunque sea su
            // primera vez en ESTA invitacion. residentPass no enrola (US-16);
            // egresos no requieren enrolamiento. En sync tambien se chequea:
            // la cache del mobile da por enrolado un DNI que no pudo verificar.
            if (!inv.residentPass()
                    && enrollments.findByPerson(neighborhoodId, inv.guestDocument()).isEmpty()) {
                return AccessDecision.NeedsEnrollment.guest(inv.id(), inv.guestName(), lot.code());
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
     * permisos. Los egresos no chequean enrolamiento.
     */
    private AccessDecision evaluateWorker(UUID neighborhoodId, ParsedQrToken parsed, Instant capturedAt,
                                          Direction direction, UUID lotId) {
        QrTokenPayload p = parsed.payload();
        Optional<WorkerTokenRepository.WorkerTokenRow> active = workerTokens.findActive(p.sub());
        if (active.isEmpty() || !active.get().jti().equals(p.jti())) {
            return AccessDecision.Denied.of(DenialReason.REVOKED);
        }
        // Segunda barrera (#211): permisos del barrio del guardia, no del token.
        Optional<WorkerValidationQuery.WorkerContext> ctxOpt = workerQuery.loadContext(p.sub(), neighborhoodId);
        if (ctxOpt.isEmpty() || ctxOpt.get().lots().isEmpty()) {
            // Invariante: worker_token activo => user tiene >=1 permiso activo
            // (UpdateWorkerUseCase revoca al llegar a 0). Si igual llega vacio,
            // fail-closed como REVOKED — nunca autorizar por omision.
            return AccessDecision.Denied.of(DenialReason.REVOKED);
        }
        WorkerValidationQuery.WorkerContext ctx = ctxOpt.get();
        // #217 / #219: en sync, capturedAt es la hora del scan, no la del sync.
        List<AccessDecision.LotOption> inSlot = WorkerLots.inSlot(ctx, capturedAt, lotId);
        if (inSlot.isEmpty()) return AccessDecision.Denied.of(DenialReason.OUT_OF_SCHEDULE);
        // #219: NEEDS_ENROLLMENT lleva los lotes; la garita manda DNI y lote juntos.
        if (direction == Direction.IN
                && enrollments.findByPerson(neighborhoodId, ctx.workerDocument()).isEmpty()) {
            return AccessDecision.NeedsEnrollment.worker(p.sub(), ctx.workerName(), inSlot);
        }
        return new AccessDecision.AllowedWorker(p.sub(), p.jti(), ctx.workerName(), inSlot);
    }
}
