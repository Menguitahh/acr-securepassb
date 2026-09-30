package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.AccessDecision;
import com.securepass.modules.access.domain.AccessLog;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.Direction;
import com.securepass.modules.access.domain.SubjectType;
import com.securepass.modules.invitation.application.InvitationRepository;
import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.modules.invitation.domain.InvitationStatus;
import com.securepass.modules.invitation.domain.InvitationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecordAccessAttemptUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-08-20T15:00:00Z");

    private ConsumeAccessUseCase consume;
    private ValidateAccessUseCase validate;
    private InvitationRepository invitations;
    private LotStatusQuery lotStatus;
    private AccessLogRepository logs;
    private RecordAccessAttemptUseCase useCase;

    private UUID neighborhoodId;
    private UUID gateId;
    private UUID guardId;
    private UUID invitationId;
    private UUID lotId;
    private UUID jti;
    private UUID clientEventId;

    @BeforeEach
    void setUp() {
        consume = mock(ConsumeAccessUseCase.class);
        validate = mock(ValidateAccessUseCase.class);
        invitations = mock(InvitationRepository.class);
        lotStatus = mock(LotStatusQuery.class);
        logs = mock(AccessLogRepository.class);
        when(logs.saveIdempotent(any())).thenAnswer(inv -> inv.getArgument(0));
        when(logs.findByClientEventId(any())).thenReturn(Optional.empty());
        useCase = new RecordAccessAttemptUseCase(consume, validate, invitations, lotStatus, logs,
                mock(StayLifecycle.class), mock(com.securepass.shared.metrics.AccessMetrics.class));

        neighborhoodId = UUID.randomUUID();
        gateId = UUID.randomUUID();
        guardId = UUID.randomUUID();
        invitationId = UUID.randomUUID();
        lotId = UUID.randomUUID();
        jti = UUID.randomUUID();
        clientEventId = UUID.randomUUID();
    }

    private RecordAccessAttemptUseCase.Command cmd(Direction dir) {
        return new RecordAccessAttemptUseCase.Command(
                neighborhoodId, gateId, guardId, "SP1.token",
                dir, 2, NOW, clientEventId, false);
    }

    private Invitation invitation() {
        return new Invitation(
                invitationId, neighborhoodId, lotId, UUID.randomUUID(),
                InvitationType.SINGLE, InvitationStatus.USED,
                "Maria Lopez", "30123456", 2, null,
                NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(6)),
                1, 1, "kid-1", jti, false, NOW);
    }

    private AccessDecision.Allowed allowed() {
        return new AccessDecision.Allowed(invitationId, jti, "Maria Lopez", "M4-L12", 2);
    }

    @Test
    void inDelegaAConsumeYPersisteLog() {
        when(consume.consume(any(), eq("SP1.token"), eq(NOW), eq(clientEventId.toString()), eq(true)))
                .thenReturn(allowed());
        when(invitations.findById(invitationId)).thenReturn(Optional.of(invitation()));

        RecordAccessAttemptUseCase.Result result = useCase.record(cmd(Direction.IN));

        assertThat(result.decision()).isInstanceOf(AccessDecision.Allowed.class);
        ArgumentCaptor<AccessLog> captor = ArgumentCaptor.forClass(AccessLog.class);
        verify(logs).saveIdempotent(captor.capture());
        AccessLog log = captor.getValue();
        assertThat(log.direction()).isEqualTo(Direction.IN);
        assertThat(log.allowed()).isTrue();
        assertThat(log.denialReason()).isNull();
        assertThat(log.invitationId()).isEqualTo(invitationId);
        assertThat(log.lotId()).isEqualTo(lotId);
        assertThat(log.tokenJti()).isEqualTo(jti);
        assertThat(log.subjectName()).isEqualTo("Maria Lopez");
        assertThat(log.subjectDocument()).isEqualTo("30123456");
        assertThat(log.subjectType()).isEqualTo(SubjectType.GUEST);
        assertThat(log.clientEventId()).isEqualTo(clientEventId);
    }

    @Test
    void outDelegaAValidateSinConsumir() {
        when(validate.validate(any(), eq("SP1.token"), eq(NOW), eq(Direction.OUT), eq(true))).thenReturn(allowed());
        when(invitations.findById(invitationId)).thenReturn(Optional.of(invitation()));

        RecordAccessAttemptUseCase.Result result = useCase.record(cmd(Direction.OUT));

        assertThat(result.decision()).isInstanceOf(AccessDecision.Allowed.class);
        verify(consume, org.mockito.Mockito.never()).consume(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(logs).saveIdempotent(any());
    }

    private AccessLog savedLog() {
        ArgumentCaptor<AccessLog> captor = ArgumentCaptor.forClass(AccessLog.class);
        verify(logs).saveIdempotent(captor.capture());
        return captor.getValue();
    }

    // #210: un denegado con pase identificado guarda el vinculo al pase, no el
    // nombre: el pase no prueba quien lo presento (REPLAY es la segunda copia).
    @ParameterizedTest
    @EnumSource(value = DenialReason.class,
            names = {"NOT_YET_VALID", "EXPIRED", "ALREADY_USED", "REPLAY", "REVOKED", "LOT_SUSPENDED"})
    void deniedConPaseGuardaInvitacionLoteYJtiSinNombreNiDocumento(DenialReason reason) {
        when(consume.consume(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(AccessDecision.Denied.of(reason, invitationId));
        when(invitations.findById(invitationId)).thenReturn(Optional.of(invitation()));

        RecordAccessAttemptUseCase.Result result = useCase.record(cmd(Direction.IN));

        AccessLog log = savedLog();
        assertThat(log.allowed()).isFalse();
        assertThat(log.denialReason()).isEqualTo(reason);
        assertThat(log.invitationId()).isEqualTo(invitationId);
        assertThat(log.lotId()).isEqualTo(lotId);
        assertThat(log.tokenJti()).isEqualTo(jti);
        assertThat(log.subjectName()).isNull();
        assertThat(log.subjectDocument()).isNull();
        // La respuesta a la garita no cambia: un denegado no lleva invitacion.
        assertThat(result.invitation()).isNull();
    }

    @Test
    void invalidSignatureGuardaLosTresEnNull() {
        when(consume.consume(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(AccessDecision.Denied.of(DenialReason.INVALID_SIGNATURE));

        useCase.record(cmd(Direction.IN));

        AccessLog log = savedLog();
        assertThat(log.denialReason()).isEqualTo(DenialReason.INVALID_SIGNATURE);
        assertThat(log.invitationId()).isNull();
        assertThat(log.lotId()).isNull();
        assertThat(log.tokenJti()).isNull();
        verify(invitations, never()).findById(any());
    }

    // Sync offline: ALREADY_USED se reclasifica como REPLAY sin perder el pase.
    @Test
    void syncReplayConservaElVinculoAlPase() {
        when(consume.consume(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(AccessDecision.Denied.of(DenialReason.ALREADY_USED, invitationId));
        when(invitations.findById(invitationId)).thenReturn(Optional.of(invitation()));
        var offline = new RecordAccessAttemptUseCase.Command(
                neighborhoodId, gateId, guardId, "SP1.token", Direction.IN, 0, NOW, clientEventId, true);

        RecordAccessAttemptUseCase.Result result = useCase.record(offline);

        assertThat(result.decision()).isEqualTo(AccessDecision.Denied.of(DenialReason.REPLAY, invitationId));
        AccessLog log = savedLog();
        assertThat(log.denialReason()).isEqualTo(DenialReason.REPLAY);
        assertThat(log.invitationId()).isEqualTo(invitationId);
        assertThat(log.lotId()).isEqualTo(lotId);
    }
}
