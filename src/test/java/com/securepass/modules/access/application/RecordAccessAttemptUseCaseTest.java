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
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
        useCase = new RecordAccessAttemptUseCase(consume, validate, invitations, lotStatus, logs);

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
                dir, 2, NOW, clientEventId);
    }

    private Invitation invitation() {
        return new Invitation(
                invitationId, neighborhoodId, lotId, UUID.randomUUID(),
                InvitationType.SINGLE, InvitationStatus.USED,
                "Maria Lopez", "30123456", 2, null,
                NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(6)),
                null, null, null, 1, 1, "kid-1", jti, false, NOW);
    }

    private AccessDecision.Allowed allowed() {
        return new AccessDecision.Allowed(invitationId, jti, "Maria Lopez", "M4-L12", 2);
    }

    @Test
    void inDelegaAConsumeYPersisteLog() {
        when(consume.consume(eq("SP1.token"), eq(NOW), eq(clientEventId.toString())))
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
        when(validate.validate(eq("SP1.token"), eq(NOW), eq(Direction.OUT))).thenReturn(allowed());
        when(invitations.findById(invitationId)).thenReturn(Optional.of(invitation()));

        RecordAccessAttemptUseCase.Result result = useCase.record(cmd(Direction.OUT));

        assertThat(result.decision()).isInstanceOf(AccessDecision.Allowed.class);
        verify(consume, org.mockito.Mockito.never()).consume(any(), any(), any());
        verify(logs).saveIdempotent(any());
    }

    @Test
    void deniedPersisteLogConDenialReason() {
        when(consume.consume(any(), any(), any()))
                .thenReturn(AccessDecision.Denied.of(DenialReason.EXPIRED));

        RecordAccessAttemptUseCase.Result result = useCase.record(cmd(Direction.IN));

        assertThat(result.decision()).isEqualTo(AccessDecision.Denied.of(DenialReason.EXPIRED));
        ArgumentCaptor<AccessLog> captor = ArgumentCaptor.forClass(AccessLog.class);
        verify(logs).saveIdempotent(captor.capture());
        AccessLog log = captor.getValue();
        assertThat(log.allowed()).isFalse();
        assertThat(log.denialReason()).isEqualTo(DenialReason.EXPIRED);
        assertThat(log.invitationId()).isNull();
        assertThat(log.tokenJti()).isNull();
    }
}
