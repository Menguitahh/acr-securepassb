package com.securepass.modules.access.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securepass.modules.access.domain.AccessLog;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.Direction;
import com.securepass.modules.access.domain.GuestEnrollment;
import com.securepass.modules.access.domain.SubjectType;
import com.securepass.modules.invitation.application.InvitationRepository;
import com.securepass.modules.invitation.application.QrTokenCodec;
import com.securepass.modules.invitation.application.QrTokenPayload;
import com.securepass.modules.invitation.application.SigningKeyRepository;
import com.securepass.modules.invitation.application.WorkerTokenRepository;
import com.securepass.modules.invitation.application.WorkerValidationQuery;
import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.modules.invitation.domain.InvitationStatus;
import com.securepass.modules.invitation.domain.InvitationType;
import com.securepass.modules.invitation.domain.SigningKey;
import com.securepass.shared.schedule.ScheduleWindow;
import com.securepass.shared.crypto.Ed25519KeyFactory;
import com.securepass.shared.crypto.Ed25519KeyPair;
import com.securepass.shared.crypto.Ed25519Signer;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.exception.StateConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnrollGuestUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-08-20T15:00:00Z");

    private QrTokenCodec codec;
    private SigningKeyRepository signingKeys;
    private InvitationRepository invitations;
    private GuestEnrollmentRepository enrollments;
    private AccessLogRepository logs;
    private WorkerTokenRepository workerTokens;
    private WorkerValidationQuery workerQuery;
    private EnrollGuestUseCase useCase;

    private UUID neighborhoodId;
    private UUID gateId;
    private UUID guardId;
    private UUID invitationId;
    private UUID clientEventId;
    private UUID lotId;
    private UUID jti;
    private Ed25519KeyPair keyPair;
    private SigningKey signingKey;

    @BeforeEach
    void setUp() {
        codec = new QrTokenCodec(new ObjectMapper());
        signingKeys = mock(SigningKeyRepository.class);
        invitations = mock(InvitationRepository.class);
        enrollments = mock(GuestEnrollmentRepository.class);
        logs = mock(AccessLogRepository.class);
        workerTokens = mock(WorkerTokenRepository.class);
        workerQuery = mock(WorkerValidationQuery.class);
        useCase = new EnrollGuestUseCase(
                codec, signingKeys, invitations, enrollments, logs, mock(StayLifecycle.class),
                workerTokens, workerQuery);
        neighborhoodId = UUID.randomUUID();
        gateId = UUID.randomUUID();
        guardId = UUID.randomUUID();
        invitationId = UUID.randomUUID();
        clientEventId = UUID.randomUUID();
        lotId = UUID.randomUUID();
        jti = UUID.randomUUID();
        keyPair = Ed25519KeyFactory.generate();
        signingKey = new SigningKey(
                UUID.randomUUID(), neighborhoodId, "kid-1",
                keyPair.publicKey(), "enc", true, NOW, null);
        when(logs.saveIdempotent(any(AccessLog.class))).thenAnswer(inv -> inv.getArgument(0));
        when(enrollments.saveIfAbsent(any(GuestEnrollment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private QrTokenPayload payloadFor(UUID invId, UUID tokenJti) {
        return new QrTokenPayload(1, "kid-1", tokenJti, neighborhoodId,
                null, null,
                lotId, invId,
                InvitationType.SINGLE.code(),
                NOW.minus(Duration.ofHours(1)).getEpochSecond(),
                NOW.plus(Duration.ofHours(6)).getEpochSecond(),
                1);
    }

    private String signed(QrTokenPayload payload) {
        byte[] bytes = codec.serialize(payload);
        byte[] sig = Ed25519Signer.sign(keyPair.privateKey(), bytes);
        return codec.encode(payload, sig);
    }

    private Invitation invitation(String document, InvitationStatus status, UUID invJti) {
        return new Invitation(
                invitationId, neighborhoodId, lotId, UUID.randomUUID(),
                InvitationType.SINGLE, status,
                "Maria Lopez", document, 2, null,
                NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(6)),
                1, 0, "kid-1", invJti, false, NOW);
    }

    private EnrollGuestUseCase.Command cmd(String token, String document) {
        return new EnrollGuestUseCase.Command(
                neighborhoodId, gateId, guardId, token, document, clientEventId, NOW, false);
    }

    private void stubKeyOk() {
        when(signingKeys.findByKid(neighborhoodId, "kid-1")).thenReturn(Optional.of(signingKey));
    }

    @Test
    void br12MatchCreaEnrolamientoYLogAllowed() {
        stubKeyOk();
        when(logs.findByClientEventId(clientEventId)).thenReturn(Optional.empty());
        when(invitations.findById(invitationId)).thenReturn(Optional.of(invitation("30123456", InvitationStatus.ACTIVE, jti)));
        String token = signed(payloadFor(invitationId, jti));

        EnrollmentResult result = useCase.enroll(cmd(token, "30123456"));

        assertThat(result).isInstanceOf(EnrollmentResult.Enrolled.class);
        EnrollmentResult.Enrolled ok = (EnrollmentResult.Enrolled) result;
        assertThat(ok.enrollment().documentNumber()).isEqualTo("30123456");
        assertThat(ok.enrollment().invitationId()).isEqualTo(invitationId);
        assertThat(ok.log().allowed()).isTrue();
        assertThat(ok.log().direction()).isEqualTo(Direction.IN);
        assertThat(ok.log().subjectType()).isEqualTo(SubjectType.GUEST);
    }

    // BR-12: DOCUMENT_MISMATCH persiste el intento.
    @Test
    void br12MismatchPersisteDocumentMismatch() {
        stubKeyOk();
        when(logs.findByClientEventId(clientEventId)).thenReturn(Optional.empty());
        when(invitations.findById(invitationId)).thenReturn(Optional.of(invitation("30123456", InvitationStatus.ACTIVE, jti)));
        String token = signed(payloadFor(invitationId, jti));

        EnrollmentResult result = useCase.enroll(cmd(token, "99999999"));

        assertThat(result).isInstanceOf(EnrollmentResult.Denied.class);
        assertThat(((EnrollmentResult.Denied) result).reason()).isEqualTo(DenialReason.DOCUMENT_MISMATCH);
        ArgumentCaptor<AccessLog> captor = ArgumentCaptor.forClass(AccessLog.class);
        verify(logs).saveIdempotent(captor.capture());
        assertThat(captor.getValue().denialReason()).isEqualTo(DenialReason.DOCUMENT_MISMATCH);
        assertThat(captor.getValue().subjectDocument()).isEqualTo("99999999");
        verify(enrollments, never()).saveIfAbsent(any());
    }

    // BR-13: retry con mismo clientEventId reconstruye sin duplicar.
    @Test
    void br13RetryConMismoClientEventIdEsIdempotente() {
        GuestEnrollment previous = new GuestEnrollment(
                UUID.randomUUID(), neighborhoodId, invitationId, "30123456", NOW, guardId, gateId);
        AccessLog previousLog = new AccessLog(
                UUID.randomUUID(), neighborhoodId, gateId, guardId, invitationId, lotId,
                Direction.IN, SubjectType.GUEST, true, null,
                "Maria Lopez", "30123456", 2, jti, NOW, false, false, clientEventId);
        when(logs.findByClientEventId(clientEventId)).thenReturn(Optional.of(previousLog));
        when(enrollments.findByPerson(neighborhoodId, "30123456")).thenReturn(Optional.of(previous));
        String token = signed(payloadFor(invitationId, jti));

        EnrollmentResult result = useCase.enroll(cmd(token, "30123456"));

        assertThat(result).isInstanceOf(EnrollmentResult.Enrolled.class);
        verify(signingKeys, never()).findByKid(any(), any());
        verify(invitations, never()).findById(any());
        verify(enrollments, never()).saveIfAbsent(any());
        verify(logs, never()).saveIdempotent(any());
    }

    // Punto 3: mismo clientEventId con documento distinto -> 409.
    @Test
    void mismoClientEventIdConDocumentoDistintoEs409() {
        AccessLog previousLog = new AccessLog(
                UUID.randomUUID(), neighborhoodId, gateId, guardId, invitationId, lotId,
                Direction.IN, SubjectType.GUEST, true, null,
                "Maria Lopez", "30123456", 2, jti, NOW, false, false, clientEventId);
        when(logs.findByClientEventId(clientEventId)).thenReturn(Optional.of(previousLog));
        String token = signed(payloadFor(invitationId, jti));

        assertThatThrownBy(() -> useCase.enroll(cmd(token, "99999999")))
                .isInstanceOf(StateConflictException.class);
        verify(signingKeys, never()).findByKid(any(), any());
        verify(invitations, never()).findById(any());
    }

    @Test
    void tokenSinPrefijoDeniegaInvalidSignatureYPersiste() {
        when(logs.findByClientEventId(clientEventId)).thenReturn(Optional.empty());

        EnrollmentResult result = useCase.enroll(cmd("no-es-un-token", "30123456"));

        assertThat(result).isInstanceOf(EnrollmentResult.Denied.class);
        assertThat(((EnrollmentResult.Denied) result).reason()).isEqualTo(DenialReason.INVALID_SIGNATURE);
        verify(invitations, never()).findById(any());
        verify(enrollments, never()).saveIfAbsent(any());
    }

    @Test
    void kidDesconocidoDeniegaInvalidSignature() {
        when(logs.findByClientEventId(clientEventId)).thenReturn(Optional.empty());
        when(signingKeys.findByKid(neighborhoodId, "kid-1")).thenReturn(Optional.empty());
        String token = signed(payloadFor(invitationId, jti));

        EnrollmentResult result = useCase.enroll(cmd(token, "30123456"));

        assertThat(result).isInstanceOf(EnrollmentResult.Denied.class);
        assertThat(((EnrollmentResult.Denied) result).reason()).isEqualTo(DenialReason.INVALID_SIGNATURE);
        verify(invitations, never()).findById(any());
    }

    @Test
    void firmaAdulteradaDeniegaInvalidSignature() {
        when(logs.findByClientEventId(clientEventId)).thenReturn(Optional.empty());
        Ed25519KeyPair otro = Ed25519KeyFactory.generate();
        SigningKey wrong = new SigningKey(
                UUID.randomUUID(), neighborhoodId, "kid-1",
                otro.publicKey(), "enc", true, NOW, null);
        when(signingKeys.findByKid(neighborhoodId, "kid-1")).thenReturn(Optional.of(wrong));
        String token = signed(payloadFor(invitationId, jti));

        EnrollmentResult result = useCase.enroll(cmd(token, "30123456"));

        assertThat(result).isInstanceOf(EnrollmentResult.Denied.class);
        assertThat(((EnrollmentResult.Denied) result).reason()).isEqualTo(DenialReason.INVALID_SIGNATURE);
        verify(invitations, never()).findById(any());
    }

    // Token de otra invitacion (jti no coincide con el persistido).
    @Test
    void jtiNoCoincideDeniegaInvalidSignatureYPersisteConInvitacion() {
        stubKeyOk();
        when(logs.findByClientEventId(clientEventId)).thenReturn(Optional.empty());
        UUID persistedJti = UUID.randomUUID();
        when(invitations.findById(invitationId)).thenReturn(Optional.of(invitation("30123456", InvitationStatus.ACTIVE, persistedJti)));
        String token = signed(payloadFor(invitationId, UUID.randomUUID())); // jti distinto

        EnrollmentResult result = useCase.enroll(cmd(token, "30123456"));

        assertThat(result).isInstanceOf(EnrollmentResult.Denied.class);
        assertThat(((EnrollmentResult.Denied) result).reason()).isEqualTo(DenialReason.INVALID_SIGNATURE);
        ArgumentCaptor<AccessLog> captor = ArgumentCaptor.forClass(AccessLog.class);
        verify(logs).saveIdempotent(captor.capture());
        assertThat(captor.getValue().invitationId()).isEqualTo(invitationId);
        verify(enrollments, never()).saveIfAbsent(any());
    }

    @Test
    void invitationInexistenteEs404() {
        stubKeyOk();
        when(logs.findByClientEventId(clientEventId)).thenReturn(Optional.empty());
        when(invitations.findById(invitationId)).thenReturn(Optional.empty());
        String token = signed(payloadFor(invitationId, jti));

        assertThatThrownBy(() -> useCase.enroll(cmd(token, "30123456")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void invitationDeOtroBarrioEs404() {
        stubKeyOk();
        when(logs.findByClientEventId(clientEventId)).thenReturn(Optional.empty());
        Invitation inv = new Invitation(
                invitationId, UUID.randomUUID(), lotId, UUID.randomUUID(),
                InvitationType.SINGLE, InvitationStatus.ACTIVE,
                "Maria Lopez", "30123456", 2, null,
                NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(6)),
                1, 0, "kid-1", jti, false, NOW);
        when(invitations.findById(invitationId)).thenReturn(Optional.of(inv));
        String token = signed(payloadFor(invitationId, jti));

        assertThatThrownBy(() -> useCase.enroll(cmd(token, "30123456")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void invitationRevokedDeniegaConRevoked() {
        stubKeyOk();
        when(logs.findByClientEventId(clientEventId)).thenReturn(Optional.empty());
        when(invitations.findById(invitationId)).thenReturn(Optional.of(invitation("30123456", InvitationStatus.REVOKED, jti)));
        String token = signed(payloadFor(invitationId, jti));

        EnrollmentResult result = useCase.enroll(cmd(token, "30123456"));

        assertThat(result).isInstanceOf(EnrollmentResult.Denied.class);
        assertThat(((EnrollmentResult.Denied) result).reason()).isEqualTo(DenialReason.REVOKED);
    }

    // -----------------------------------------------------------------
    // WBS-122: enrolamiento por el ramo del trabajador (subType=W).
    // -----------------------------------------------------------------

    private String signedWorkerToken(UUID userId, UUID theJti) {
        QrTokenPayload p = QrTokenPayload.worker(1, "kid-1", theJti, neighborhoodId, userId);
        return signed(p);
    }

    private WorkerValidationQuery.WorkerContext workerCtx(String name, String document) {
        return new WorkerValidationQuery.WorkerContext(
                name, document, ZoneId.of("America/Argentina/Mendoza"),
                List.of(new WorkerValidationQuery.ActiveWorkerLot(
                        UUID.randomUUID(), "L-1", "Jardineria",
                        new ScheduleWindow(List.of(1, 2, 3, 4, 5, 6, 7),
                                LocalTime.of(0, 0), LocalTime.of(23, 59)))));
    }

    @Test
    void workerMatchGuardaEnrolamientoSinInvitationYLogueaService() {
        stubKeyOk();
        UUID userId = UUID.randomUUID();
        UUID workerJti = UUID.randomUUID();
        when(logs.findByClientEventId(clientEventId)).thenReturn(Optional.empty());
        when(workerTokens.findActive(userId)).thenReturn(Optional.of(
                new WorkerTokenRepository.WorkerTokenRow(userId, neighborhoodId, workerJti, NOW)));
        when(workerQuery.loadContext(userId, neighborhoodId))
                .thenReturn(Optional.of(workerCtx("Juan Jardinero", "30123456")));

        EnrollmentResult result = useCase.enroll(cmd(signedWorkerToken(userId, workerJti), "30123456"));

        assertThat(result).isInstanceOf(EnrollmentResult.Enrolled.class);
        EnrollmentResult.Enrolled ok = (EnrollmentResult.Enrolled) result;
        assertThat(ok.enrollment().documentNumber()).isEqualTo("30123456");
        assertThat(ok.enrollment().invitationId()).isNull(); // ramo worker
        assertThat(ok.enrollment().neighborhoodId()).isEqualTo(neighborhoodId);
        ArgumentCaptor<AccessLog> captor = ArgumentCaptor.forClass(AccessLog.class);
        verify(logs).saveIdempotent(captor.capture());
        AccessLog log = captor.getValue();
        assertThat(log.allowed()).isTrue();
        assertThat(log.subjectType()).isEqualTo(SubjectType.SERVICE);
        assertThat(log.invitationId()).isNull();
        assertThat(log.lotId()).isNull(); // deliberado: proxima entrada resuelve franja
        assertThat(log.subjectName()).isEqualTo("Juan Jardinero");
        assertThat(log.tokenJti()).isEqualTo(workerJti);
    }

    // El caso "le prestaron el QR a otro": el guardia carga un DNI distinto
    // al del user del token. Es exactamente lo que el enrolamiento detecta.
    @Test
    void workerMismatchPersisteDocumentMismatchYNoEnrola() {
        stubKeyOk();
        UUID userId = UUID.randomUUID();
        UUID workerJti = UUID.randomUUID();
        when(logs.findByClientEventId(clientEventId)).thenReturn(Optional.empty());
        when(workerTokens.findActive(userId)).thenReturn(Optional.of(
                new WorkerTokenRepository.WorkerTokenRow(userId, neighborhoodId, workerJti, NOW)));
        when(workerQuery.loadContext(userId, neighborhoodId))
                .thenReturn(Optional.of(workerCtx("Juan Jardinero", "30123456")));

        EnrollmentResult result = useCase.enroll(cmd(signedWorkerToken(userId, workerJti), "99999999"));

        assertThat(result).isInstanceOf(EnrollmentResult.Denied.class);
        assertThat(((EnrollmentResult.Denied) result).reason()).isEqualTo(DenialReason.DOCUMENT_MISMATCH);
        ArgumentCaptor<AccessLog> captor = ArgumentCaptor.forClass(AccessLog.class);
        verify(logs).saveIdempotent(captor.capture());
        AccessLog log = captor.getValue();
        assertThat(log.subjectType()).isEqualTo(SubjectType.SERVICE);
        assertThat(log.denialReason()).isEqualTo(DenialReason.DOCUMENT_MISMATCH);
        assertThat(log.subjectDocument()).isEqualTo("99999999");
        assertThat(log.tokenJti()).isEqualTo(workerJti);
        verify(enrollments, never()).saveIfAbsent(any());
    }

    @Test
    void workerJtiRotadoDeniegaRevoked() {
        stubKeyOk();
        UUID userId = UUID.randomUUID();
        UUID staleJti = UUID.randomUUID();
        UUID currentActiveJti = UUID.randomUUID();
        when(logs.findByClientEventId(clientEventId)).thenReturn(Optional.empty());
        when(workerTokens.findActive(userId)).thenReturn(Optional.of(
                new WorkerTokenRepository.WorkerTokenRow(userId, neighborhoodId, currentActiveJti, NOW)));

        EnrollmentResult result = useCase.enroll(cmd(signedWorkerToken(userId, staleJti), "30123456"));

        assertThat(result).isInstanceOf(EnrollmentResult.Denied.class);
        assertThat(((EnrollmentResult.Denied) result).reason()).isEqualTo(DenialReason.REVOKED);
        verify(enrollments, never()).saveIfAbsent(any());
    }

    @Test
    void workerRetryConMismoClientEventIdBuscaPorDocumento() {
        UUID userId = UUID.randomUUID();
        UUID workerJti = UUID.randomUUID();
        GuestEnrollment previous = new GuestEnrollment(
                UUID.randomUUID(), neighborhoodId, null, "30123456", NOW, guardId, gateId);
        AccessLog previousLog = new AccessLog(
                UUID.randomUUID(), neighborhoodId, gateId, guardId, null, null,
                Direction.IN, SubjectType.SERVICE, true, null,
                "Juan Jardinero", "30123456", 0, workerJti, NOW, false, false, clientEventId);
        when(logs.findByClientEventId(clientEventId)).thenReturn(Optional.of(previousLog));
        when(enrollments.findByPerson(neighborhoodId, "30123456")).thenReturn(Optional.of(previous));

        EnrollmentResult result = useCase.enroll(cmd(signedWorkerToken(userId, workerJti), "30123456"));

        assertThat(result).isInstanceOf(EnrollmentResult.Enrolled.class);
        verify(signingKeys, never()).findByKid(any(), any());
        verify(workerTokens, never()).findActive(any());
        verify(enrollments, never()).saveIfAbsent(any());
    }
}
