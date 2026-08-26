package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.AccessDecision;
import com.securepass.modules.access.domain.AccessLog;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.Direction;
import com.securepass.modules.access.domain.SubjectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncAccessUseCaseTest {

    private static final Instant T0 = Instant.parse("2026-08-20T15:00:00Z");

    private RecordAccessAttemptUseCase recorder;
    private SyncAccessUseCase useCase;

    private UUID neighborhoodId;
    private UUID guardId;
    private UUID gateId;
    private UUID lotId;
    private UUID invitationId;
    private UUID jti;

    @BeforeEach
    void setUp() {
        recorder = mock(RecordAccessAttemptUseCase.class);
        useCase = new SyncAccessUseCase(recorder);
        neighborhoodId = UUID.randomUUID();
        guardId = UUID.randomUUID();
        gateId = UUID.randomUUID();
        lotId = UUID.randomUUID();
        invitationId = UUID.randomUUID();
        jti = UUID.randomUUID();
    }

    @Test
    void ordenaPorCapturedAtAntesDeGrabar() {
        SyncAccessUseCase.Event later = event(UUID.randomUUID(), T0.plusSeconds(60));
        SyncAccessUseCase.Event earlier = event(UUID.randomUUID(), T0);
        when(recorder.record(any())).thenAnswer(inv -> allowedResult(inv.getArgument(0)));

        useCase.sync(neighborhoodId, guardId, List.of(later, earlier));

        ArgumentCaptor<RecordAccessAttemptUseCase.Command> captor =
                ArgumentCaptor.forClass(RecordAccessAttemptUseCase.Command.class);
        verify(recorder, org.mockito.Mockito.times(2)).record(captor.capture());
        List<RecordAccessAttemptUseCase.Command> ordered = captor.getAllValues();
        assertThat(ordered.get(0).clientEventId()).isEqualTo(earlier.clientEventId());
        assertThat(ordered.get(1).clientEventId()).isEqualTo(later.clientEventId());
    }

    @Test
    void marcaSyncedOfflineTrue() {
        SyncAccessUseCase.Event ev = event(UUID.randomUUID(), T0);
        when(recorder.record(any())).thenAnswer(inv -> allowedResult(inv.getArgument(0)));

        useCase.sync(neighborhoodId, guardId, List.of(ev));

        ArgumentCaptor<RecordAccessAttemptUseCase.Command> captor =
                ArgumentCaptor.forClass(RecordAccessAttemptUseCase.Command.class);
        verify(recorder).record(captor.capture());
        assertThat(captor.getValue().syncedOffline()).isTrue();
    }

    @Test
    void deniedSePersisteYAparaceEnConflicts() {
        SyncAccessUseCase.Event ok = event(UUID.randomUUID(), T0);
        SyncAccessUseCase.Event dup = event(UUID.randomUUID(), T0.plusSeconds(10));
        when(recorder.record(argWithClient(ok.clientEventId())))
                .thenReturn(allowedResult(commandFor(ok)));
        when(recorder.record(argWithClient(dup.clientEventId())))
                .thenReturn(deniedResult(commandFor(dup), DenialReason.REPLAY, false));

        SyncAccessUseCase.Report report = useCase.sync(neighborhoodId, guardId, List.of(ok, dup));

        assertThat(report.accepted()).isEqualTo(1);
        assertThat(report.conflicts()).hasSize(1);
        SyncAccessUseCase.Conflict c = report.conflicts().get(0);
        assertThat(c.clientEventId()).isEqualTo(dup.clientEventId());
        assertThat(c.reason()).isEqualTo(DenialReason.REPLAY);
        assertThat(c.logId()).isNotNull();
    }

    @Test
    void batchExcedidoRompeAntesDeGrabar() {
        List<SyncAccessUseCase.Event> big = new ArrayList<>();
        for (int i = 0; i <= SyncAccessUseCase.MAX_BATCH; i++) {
            big.add(event(UUID.randomUUID(), T0.plusSeconds(i)));
        }
        assertThatThrownBy(() -> useCase.sync(neighborhoodId, guardId, big))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch");
        verify(recorder, org.mockito.Mockito.never()).record(any());
    }

    @Test
    void clockSkewSeReportaEnConflict() {
        SyncAccessUseCase.Event ev = event(UUID.randomUUID(), T0.minusSeconds(3600));
        when(recorder.record(any()))
                .thenReturn(deniedResult(commandFor(ev), DenialReason.EXPIRED, true));

        SyncAccessUseCase.Report report = useCase.sync(neighborhoodId, guardId, List.of(ev));

        assertThat(report.conflicts()).hasSize(1);
        assertThat(report.conflicts().get(0).clockSkew()).isTrue();
    }

    private static org.mockito.ArgumentMatcher<RecordAccessAttemptUseCase.Command> withClient(UUID id) {
        return c -> c != null && id.equals(c.clientEventId());
    }

    private static RecordAccessAttemptUseCase.Command argWithClient(UUID id) {
        return org.mockito.ArgumentMatchers.argThat(withClient(id));
    }

    private SyncAccessUseCase.Event event(UUID clientEventId, Instant capturedAt) {
        return new SyncAccessUseCase.Event(
                clientEventId, "SP1.token", gateId, Direction.IN, 0, capturedAt, "ALLOWED");
    }

    private RecordAccessAttemptUseCase.Command commandFor(SyncAccessUseCase.Event ev) {
        return new RecordAccessAttemptUseCase.Command(
                neighborhoodId, ev.gateId(), guardId, ev.token(), ev.direction(),
                ev.companions(), ev.capturedAt(), ev.clientEventId(), true);
    }

    private RecordAccessAttemptUseCase.Result allowedResult(RecordAccessAttemptUseCase.Command cmd) {
        AccessDecision.Allowed allowed = new AccessDecision.Allowed(
                invitationId, jti, "Maria", "M4-L12", cmd.companions());
        AccessLog log = new AccessLog(
                UUID.randomUUID(), neighborhoodId, cmd.gateId(), guardId,
                invitationId, lotId, cmd.direction(), SubjectType.GUEST,
                true, null, "Maria", "30123456", cmd.companions(),
                jti, cmd.capturedAt(), true, false, cmd.clientEventId());
        return new RecordAccessAttemptUseCase.Result(allowed, log, null);
    }

    private RecordAccessAttemptUseCase.Result deniedResult(
            RecordAccessAttemptUseCase.Command cmd, DenialReason reason, boolean skew) {
        AccessLog log = new AccessLog(
                UUID.randomUUID(), neighborhoodId, cmd.gateId(), guardId,
                null, null, cmd.direction(), SubjectType.GUEST,
                false, reason, null, null, cmd.companions(),
                null, cmd.capturedAt(), true, skew, cmd.clientEventId());
        return new RecordAccessAttemptUseCase.Result(AccessDecision.Denied.of(reason), log, null);
    }
}
