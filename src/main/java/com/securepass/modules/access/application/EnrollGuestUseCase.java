package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.AccessLog;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.Direction;
import com.securepass.modules.access.domain.GuestEnrollment;
import com.securepass.modules.access.domain.SubjectType;
import com.securepass.modules.invitation.application.InvitationRepository;
import com.securepass.modules.invitation.application.ParsedQrToken;
import com.securepass.modules.invitation.application.QrTokenCodec;
import com.securepass.modules.invitation.application.SigningKeyRepository;
import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.modules.invitation.domain.InvitationStatus;
import com.securepass.modules.invitation.domain.SigningKey;
import com.securepass.shared.crypto.Ed25519Verifier;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.exception.StateConflictException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class EnrollGuestUseCase {

    private final QrTokenCodec codec;
    private final SigningKeyRepository signingKeys;
    private final InvitationRepository invitations;
    private final GuestEnrollmentRepository enrollments;
    private final AccessLogRepository logs;

    public EnrollGuestUseCase(
            QrTokenCodec codec,
            SigningKeyRepository signingKeys,
            InvitationRepository invitations,
            GuestEnrollmentRepository enrollments,
            AccessLogRepository logs) {
        this.codec = codec;
        this.signingKeys = signingKeys;
        this.invitations = invitations;
        this.enrollments = enrollments;
        this.logs = logs;
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
            return reconstruct(existing.get());
        }

        Optional<ParsedQrToken> parsedOpt = codec.parse(cmd.token());
        if (parsedOpt.isEmpty()) return denyUnknownInvitation(cmd, DenialReason.INVALID_SIGNATURE);
        ParsedQrToken parsed = parsedOpt.get();

        Optional<SigningKey> keyOpt = signingKeys.findByKid(
                parsed.payload().neighborhoodId(), parsed.payload().kid());
        if (keyOpt.isEmpty()) return denyUnknownInvitation(cmd, DenialReason.INVALID_SIGNATURE);
        if (!Ed25519Verifier.verify(keyOpt.get().publicKey(), parsed.payloadBytes(), parsed.signature())) {
            return denyUnknownInvitation(cmd, DenialReason.INVALID_SIGNATURE);
        }

        Invitation inv = invitations.findById(parsed.payload().invitationId())
                .orElseThrow(() -> new ResourceNotFoundException("invitation", parsed.payload().invitationId()));
        if (!inv.jti().equals(parsed.payload().jti())) {
            return denyAndLog(cmd, inv, DenialReason.INVALID_SIGNATURE);
        }
        if (!inv.neighborhoodId().equals(cmd.neighborhoodId())) {
            throw new ResourceNotFoundException("invitation", inv.id());
        }
        if (inv.status() != InvitationStatus.ACTIVE) {
            return denyAndLog(cmd, inv, DenialReason.REVOKED);
        }
        if (!cmd.documentNumber().equals(inv.guestDocument())) {
            // BR-12: no coincide -> DOCUMENT_MISMATCH y persistencia obligatoria.
            return denyAndLog(cmd, inv, DenialReason.DOCUMENT_MISMATCH);
        }

        GuestEnrollment enrollment = enrollments.saveIfAbsent(new GuestEnrollment(
                UUID.randomUUID(), inv.id(), cmd.documentNumber(),
                Instant.now(), cmd.guardId(), cmd.gateId()));
        AccessLog log = logs.saveIdempotent(new AccessLog(
                UUID.randomUUID(), cmd.neighborhoodId(), cmd.gateId(), cmd.guardId(),
                inv.id(), inv.lotId(),
                Direction.IN, SubjectType.GUEST, true, null,
                inv.guestName(), cmd.documentNumber(), inv.companions(),
                inv.jti(), cmd.capturedAt(), false, false, cmd.clientEventId()));
        return new EnrollmentResult.Enrolled(enrollment, log, inv.guestName());
    }

    private EnrollmentResult denyUnknownInvitation(Command cmd, DenialReason reason) {
        AccessLog log = logs.saveIdempotent(new AccessLog(
                UUID.randomUUID(), cmd.neighborhoodId(), cmd.gateId(), cmd.guardId(),
                null, null,
                Direction.IN, SubjectType.GUEST, false, reason,
                null, cmd.documentNumber(), 0,
                null, cmd.capturedAt(), false, false, cmd.clientEventId()));
        return new EnrollmentResult.Denied(reason, log);
    }

    private EnrollmentResult denyAndLog(Command cmd, Invitation inv, DenialReason reason) {
        AccessLog log = logs.saveIdempotent(new AccessLog(
                UUID.randomUUID(), cmd.neighborhoodId(), cmd.gateId(), cmd.guardId(),
                inv.id(), inv.lotId(),
                Direction.IN, SubjectType.GUEST, false, reason,
                null, cmd.documentNumber(), 0,
                inv.jti(), cmd.capturedAt(), false, false, cmd.clientEventId()));
        return new EnrollmentResult.Denied(reason, log);
    }

    private EnrollmentResult reconstruct(AccessLog log) {
        if (!log.allowed()) return new EnrollmentResult.Denied(log.denialReason(), log);
        GuestEnrollment enrollment = enrollments.findByInvitationId(log.invitationId())
                .orElseThrow(() -> new IllegalStateException(
                        "log ALLOWED sin enrolamiento para invitation " + log.invitationId()));
        return new EnrollmentResult.Enrolled(enrollment, log, log.subjectName());
    }

    public record Command(
            UUID neighborhoodId,
            UUID gateId,
            UUID guardId,
            String token,
            String documentNumber,
            UUID clientEventId,
            Instant capturedAt
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
