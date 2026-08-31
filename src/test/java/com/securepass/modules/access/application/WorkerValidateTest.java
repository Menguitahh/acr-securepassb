package com.securepass.modules.access.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securepass.modules.access.domain.AccessDecision;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.Direction;
import com.securepass.modules.invitation.application.InvitationRepository;
import com.securepass.modules.invitation.application.QrTokenCodec;
import com.securepass.modules.invitation.application.QrTokenPayload;
import com.securepass.modules.invitation.application.SigningKeyRepository;
import com.securepass.modules.invitation.application.WorkerTokenRepository;
import com.securepass.modules.invitation.application.WorkerValidationQuery;
import com.securepass.modules.invitation.domain.SigningKey;
import com.securepass.shared.crypto.Ed25519KeyFactory;
import com.securepass.shared.crypto.Ed25519KeyPair;
import com.securepass.shared.crypto.Ed25519Signer;
import com.securepass.shared.schedule.ScheduleWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WBS-117: validacion del QR del trabajador — cinco escenarios que el guardia
 * puede encontrar en garita, mockeando el mundo alrededor de
 * ValidateAccessUseCase.
 */
class WorkerValidateTest {

    private static final ZoneId MZA = ZoneId.of("America/Argentina/Mendoza");
    private static final String WORKER_NAME = "Juan Jardinero";

    private ValidateAccessUseCase useCase;
    private SigningKeyRepository signingKeys;
    private GuestEnrollmentRepository enrollments;
    private WorkerTokenRepository workerTokens;
    private WorkerValidationQuery workerQuery;

    private QrTokenCodec codec;
    private Ed25519KeyPair kp;
    private SigningKey key;
    private UUID nbh;
    private UUID userId;
    private UUID jti;

    @BeforeEach
    void setUp() {
        codec = new QrTokenCodec(new ObjectMapper());
        signingKeys = mock(SigningKeyRepository.class);
        InvitationRepository invitations = mock(InvitationRepository.class);
        LotStatusQuery lots = mock(LotStatusQuery.class);
        enrollments = mock(GuestEnrollmentRepository.class);
        workerTokens = mock(WorkerTokenRepository.class);
        workerQuery = mock(WorkerValidationQuery.class);
        useCase = new ValidateAccessUseCase(
                codec, signingKeys, invitations, lots, enrollments, workerTokens, workerQuery);
        // Por defecto, worker ya enrolado — los tests que validan NEEDS_ENROLLMENT
        // stubbean explicito con Optional.empty.
        when(enrollments.findByPerson(any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(new com.securepass.modules.access.domain.GuestEnrollment(
                        UUID.randomUUID(), UUID.randomUUID(), null, "30123456",
                        Instant.parse("2026-08-01T00:00:00Z"), null, null)));

        kp = Ed25519KeyFactory.generate();
        nbh = UUID.randomUUID();
        userId = UUID.randomUUID();
        jti = UUID.randomUUID();
        key = new SigningKey(UUID.randomUUID(), nbh, "kid-1",
                kp.publicKey(), "enc", true, Instant.now(), null);
        when(signingKeys.findByKid(nbh, "kid-1")).thenReturn(Optional.of(key));
    }

    private String workerToken(UUID neighborhoodId, UUID subject, UUID theJti) {
        QrTokenPayload p = QrTokenPayload.worker(1, "kid-1", theJti, neighborhoodId, subject);
        byte[] bytes = codec.serialize(p);
        byte[] sig = Ed25519Signer.sign(kp.privateKey(), bytes);
        return codec.encode(p, sig);
    }

    private WorkerValidationQuery.ActiveWorkerLot lot(String code, List<Integer> weekdays,
                                                      LocalTime from, LocalTime until) {
        return new WorkerValidationQuery.ActiveWorkerLot(
                UUID.randomUUID(), code, "Jardineria",
                new ScheduleWindow(weekdays, from, until));
    }

    @Test
    void dentroDeFranjaAllowedConUnSoloLote() {
        // Martes 2026-03-03 10:00 Mendoza = 13:00Z.
        Instant capturedAt = Instant.parse("2026-03-03T13:00:00Z");
        when(workerTokens.findActive(userId)).thenReturn(Optional.of(
                new WorkerTokenRepository.WorkerTokenRow(userId, nbh, jti, Instant.now())));
        when(workerQuery.loadContext(userId, nbh)).thenReturn(Optional.of(
                new WorkerValidationQuery.WorkerContext(
                        WORKER_NAME, "30123456", MZA,
                        List.of(lot("L-1", List.of(2, 4), LocalTime.of(8, 0), LocalTime.of(12, 0))))));

        AccessDecision d = useCase.validate(workerToken(nbh, userId, jti), capturedAt, Direction.IN);

        assertThat(d).isInstanceOf(AccessDecision.AllowedWorker.class);
        AccessDecision.AllowedWorker w = (AccessDecision.AllowedWorker) d;
        assertThat(w.lots()).hasSize(1);
        assertThat(w.lots().get(0).lotCode()).isEqualTo("L-1");
        assertThat(w.jti()).isEqualTo(jti);
        assertThat(w.workerName()).isEqualTo(WORKER_NAME);
    }

    @Test
    void fueraDeFranjaOutOfSchedule() {
        // Miercoles fuera del set (martes+jueves).
        Instant capturedAt = Instant.parse("2026-03-04T13:00:00Z");
        when(workerTokens.findActive(userId)).thenReturn(Optional.of(
                new WorkerTokenRepository.WorkerTokenRow(userId, nbh, jti, Instant.now())));
        when(workerQuery.loadContext(userId, nbh)).thenReturn(Optional.of(
                new WorkerValidationQuery.WorkerContext(
                        WORKER_NAME, "30123456", MZA,
                        List.of(lot("L-1", List.of(2, 4), LocalTime.of(8, 0), LocalTime.of(12, 0))))));

        AccessDecision d = useCase.validate(workerToken(nbh, userId, jti), capturedAt, Direction.IN);

        assertThat(d).isEqualTo(AccessDecision.Denied.of(DenialReason.OUT_OF_SCHEDULE));
    }

    @Test
    void multiLoteDosEnFranjaSimultaneaDevuelveDos() {
        // Martes 10:00 Mza — matchea L-1 (mar 8-12) y L-2 (mar 9-13). L-3 solo miercoles.
        Instant capturedAt = Instant.parse("2026-03-03T13:00:00Z");
        when(workerTokens.findActive(userId)).thenReturn(Optional.of(
                new WorkerTokenRepository.WorkerTokenRow(userId, nbh, jti, Instant.now())));
        when(workerQuery.loadContext(userId, nbh)).thenReturn(Optional.of(
                new WorkerValidationQuery.WorkerContext(
                        WORKER_NAME, "30123456", MZA,
                        List.of(
                                lot("L-1", List.of(2), LocalTime.of(8, 0), LocalTime.of(12, 0)),
                                lot("L-2", List.of(2), LocalTime.of(9, 0), LocalTime.of(13, 0)),
                                lot("L-3", List.of(3), LocalTime.of(9, 0), LocalTime.of(13, 0))))));

        AccessDecision d = useCase.validate(workerToken(nbh, userId, jti), capturedAt, Direction.IN);

        assertThat(d).isInstanceOf(AccessDecision.AllowedWorker.class);
        AccessDecision.AllowedWorker w = (AccessDecision.AllowedWorker) d;
        assertThat(w.lots()).extracting(AccessDecision.LotOption::lotCode)
                .containsExactlyInAnyOrder("L-1", "L-2");
    }

    @Test
    void sinPermisosActivosDeniedRevoked() {
        Instant capturedAt = Instant.parse("2026-03-03T13:00:00Z");
        when(workerTokens.findActive(userId)).thenReturn(Optional.of(
                new WorkerTokenRepository.WorkerTokenRow(userId, nbh, jti, Instant.now())));
        // Contexto valido pero sin lotes -> invariante rota, fail-closed REVOKED.
        when(workerQuery.loadContext(userId, nbh)).thenReturn(Optional.of(
                new WorkerValidationQuery.WorkerContext(WORKER_NAME, "30123456", MZA, List.of())));

        AccessDecision d = useCase.validate(workerToken(nbh, userId, jti), capturedAt, Direction.IN);

        assertThat(d).isEqualTo(AccessDecision.Denied.of(DenialReason.REVOKED));
    }

    @Test
    void jtiRotadoDeniedRevoked() {
        // El worker_token activo tiene OTRO jti (el titular dio de baja al
        // worker, se emitio uno nuevo despues). El QR viejo es basura.
        UUID currentActiveJti = UUID.randomUUID();
        Instant capturedAt = Instant.parse("2026-03-03T13:00:00Z");
        when(workerTokens.findActive(userId)).thenReturn(Optional.of(
                new WorkerTokenRepository.WorkerTokenRow(userId, nbh, currentActiveJti, Instant.now())));

        AccessDecision d = useCase.validate(workerToken(nbh, userId, jti), capturedAt, Direction.IN);

        assertThat(d).isEqualTo(AccessDecision.Denied.of(DenialReason.REVOKED));
    }

    @Test
    void sinWorkerTokenActivoDeniedRevoked() {
        // No hay fila activa en worker_tokens (fue revocado por rotacion, o nunca existio).
        Instant capturedAt = Instant.parse("2026-03-03T13:00:00Z");
        when(workerTokens.findActive(any())).thenReturn(Optional.empty());

        AccessDecision d = useCase.validate(workerToken(nbh, userId, jti), capturedAt, Direction.IN);

        assertThat(d).isEqualTo(AccessDecision.Denied.of(DenialReason.REVOKED));
    }

    // WBS-122: dentro de franja pero persona no verificada en el barrio ->
    // NEEDS_ENROLLMENT por el ramo WORKER. No adelanta el nombre (misma regla
    // que el invitado en AccessDecision).
    @Test
    void dentroDeFranjaSinEnrolamientoDevuelveNeedsEnrollmentWorker() {
        Instant capturedAt = Instant.parse("2026-03-03T13:00:00Z");
        GuestEnrollmentRepository localEnrollments = mock(GuestEnrollmentRepository.class);
        when(localEnrollments.findByPerson(nbh, "30123456")).thenReturn(Optional.empty());
        ValidateAccessUseCase localUseCase = new ValidateAccessUseCase(
                codec, signingKeys, mock(InvitationRepository.class), mock(LotStatusQuery.class),
                localEnrollments, workerTokens, workerQuery);

        when(workerTokens.findActive(userId)).thenReturn(Optional.of(
                new WorkerTokenRepository.WorkerTokenRow(userId, nbh, jti, Instant.now())));
        when(workerQuery.loadContext(userId, nbh)).thenReturn(Optional.of(
                new WorkerValidationQuery.WorkerContext(
                        WORKER_NAME, "30123456", MZA,
                        List.of(lot("L-1", List.of(2, 4), LocalTime.of(8, 0), LocalTime.of(12, 0))))));

        AccessDecision d = localUseCase.validate(workerToken(nbh, userId, jti), capturedAt, Direction.IN);

        assertThat(d).isInstanceOf(AccessDecision.NeedsEnrollment.class);
        AccessDecision.NeedsEnrollment n = (AccessDecision.NeedsEnrollment) d;
        assertThat(n.kind()).isEqualTo(AccessDecision.SubjectKind.WORKER);
        assertThat(n.userId()).isEqualTo(userId);
        assertThat(n.invitationId()).isNull();
        assertThat(n.lotCode()).isNull();
    }
}
