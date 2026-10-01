package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.AccessDecision;
import com.securepass.modules.access.domain.AccessLog;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.Direction;
import com.securepass.modules.access.domain.GuestEnrollment;
import com.securepass.modules.access.domain.SubjectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncAccessUseCaseTest {

    private static final Instant T0 = Instant.parse("2026-08-20T15:00:00Z");

    private RecordAccessAttemptUseCase recorder;
    private EnrollGuestUseCase enroller;
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
        enroller = mock(EnrollGuestUseCase.class);
        useCase = new SyncAccessUseCase(recorder, enroller, mock(AccessLogRepository.class),
                mock(com.securepass.shared.metrics.AccessMetrics.class));
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
        when(recorder.record(any())).thenAnswer(inv -> allowedResult(inv.getArgument(0), false));

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
        when(recorder.record(any())).thenAnswer(inv -> allowedResult(inv.getArgument(0), false));

        useCase.sync(neighborhoodId, guardId, List.of(ev));

        ArgumentCaptor<RecordAccessAttemptUseCase.Command> captor =
                ArgumentCaptor.forClass(RecordAccessAttemptUseCase.Command.class);
        verify(recorder).record(captor.capture());
        assertThat(captor.getValue().syncedOffline()).isTrue();
    }

    @Test
    void allowedYDeniedConvivenEnResultsPreservandoClientEventId() {
        SyncAccessUseCase.Event ok = event(UUID.randomUUID(), T0);
        SyncAccessUseCase.Event dup = event(UUID.randomUUID(), T0.plusSeconds(10));
        when(recorder.record(argWithClient(ok.clientEventId())))
                .thenReturn(allowedResult(commandFor(ok), false));
        when(recorder.record(argWithClient(dup.clientEventId())))
                .thenReturn(deniedResult(commandFor(dup), DenialReason.REPLAY, false));

        SyncAccessUseCase.Report report = useCase.sync(neighborhoodId, guardId, List.of(ok, dup));

        assertThat(report.results()).hasSize(2);
        Map<UUID, SyncAccessUseCase.EventResult> byId = report.results().stream()
                .collect(Collectors.toMap(SyncAccessUseCase.EventResult::clientEventId, Function.identity()));

        SyncAccessUseCase.EventResult okRes = byId.get(ok.clientEventId());
        assertThat(okRes.result()).isEqualTo(SyncAccessUseCase.Outcome.ALLOWED);
        assertThat(okRes.reason()).isNull();
        assertThat(okRes.logId()).isNotNull();
        assertThat(okRes.enrollmentId()).isNull();

        SyncAccessUseCase.EventResult dupRes = byId.get(dup.clientEventId());
        assertThat(dupRes.result()).isEqualTo(SyncAccessUseCase.Outcome.DENIED);
        assertThat(dupRes.reason()).isEqualTo(DenialReason.REPLAY);
        assertThat(dupRes.logId()).isNotNull();
    }

    @Test
    void batchMixtoAllowedDeniedNoPierdeNingunItem() {
        SyncAccessUseCase.Event a1 = event(UUID.randomUUID(), T0.plusSeconds(1));
        SyncAccessUseCase.Event d1 = event(UUID.randomUUID(), T0.plusSeconds(2));
        SyncAccessUseCase.Event a2 = event(UUID.randomUUID(), T0.plusSeconds(3));
        SyncAccessUseCase.Event d2 = event(UUID.randomUUID(), T0.plusSeconds(4));
        when(recorder.record(argWithClient(a1.clientEventId()))).thenReturn(allowedResult(commandFor(a1), false));
        when(recorder.record(argWithClient(d1.clientEventId()))).thenReturn(deniedResult(commandFor(d1), DenialReason.EXPIRED, false));
        when(recorder.record(argWithClient(a2.clientEventId()))).thenReturn(allowedResult(commandFor(a2), false));
        when(recorder.record(argWithClient(d2.clientEventId()))).thenReturn(deniedResult(commandFor(d2), DenialReason.DOCUMENT_MISMATCH, false));

        SyncAccessUseCase.Report report = useCase.sync(neighborhoodId, guardId, List.of(a1, d1, a2, d2));

        assertThat(report.results()).hasSize(4);
        List<UUID> ids = report.results().stream().map(SyncAccessUseCase.EventResult::clientEventId).toList();
        assertThat(ids).containsExactlyInAnyOrder(
                a1.clientEventId(), d1.clientEventId(), a2.clientEventId(), d2.clientEventId());

        Map<UUID, SyncAccessUseCase.EventResult> byId = report.results().stream()
                .collect(Collectors.toMap(SyncAccessUseCase.EventResult::clientEventId, Function.identity()));

        for (UUID okId : List.of(a1.clientEventId(), a2.clientEventId())) {
            SyncAccessUseCase.EventResult r = byId.get(okId);
            assertThat(r.result()).isEqualTo(SyncAccessUseCase.Outcome.ALLOWED);
            assertThat(r.reason()).isNull();
            assertThat(r.logId()).isNotNull();
            assertThat(r.enrollmentId()).isNull();
        }
        assertThat(byId.get(d1.clientEventId()).reason()).isEqualTo(DenialReason.EXPIRED);
        assertThat(byId.get(d2.clientEventId()).reason()).isEqualTo(DenialReason.DOCUMENT_MISMATCH);
        for (UUID denId : List.of(d1.clientEventId(), d2.clientEventId())) {
            SyncAccessUseCase.EventResult r = byId.get(denId);
            assertThat(r.result()).isEqualTo(SyncAccessUseCase.Outcome.DENIED);
            assertThat(r.logId()).isNotNull();
        }
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
    void clockSkewSeReportaEnResult() {
        SyncAccessUseCase.Event ev = event(UUID.randomUUID(), T0.minusSeconds(3600));
        when(recorder.record(any()))
                .thenReturn(deniedResult(commandFor(ev), DenialReason.EXPIRED, true));

        SyncAccessUseCase.Report report = useCase.sync(neighborhoodId, guardId, List.of(ev));

        assertThat(report.results()).hasSize(1);
        SyncAccessUseCase.EventResult r = report.results().get(0);
        assertThat(r.result()).isEqualTo(SyncAccessUseCase.Outcome.DENIED);
        assertThat(r.reason()).isEqualTo(DenialReason.EXPIRED);
        assertThat(r.clockSkew()).isTrue();
    }

    @Test
    void eventConDocumentNumberRuteaAEnrollerYExponeEnrollmentId() {
        UUID enrollClientId = UUID.randomUUID();
        SyncAccessUseCase.Event enrollEv = new SyncAccessUseCase.Event(
                enrollClientId, "SP1.token", gateId, Direction.IN, 0, T0, SyncAccessUseCase.LocalDecision.ALLOWED, null, "30123456", null);
        UUID enrollmentId = UUID.randomUUID();
        UUID logId = UUID.randomUUID();
        GuestEnrollment ge = new GuestEnrollment(
                enrollmentId, neighborhoodId, invitationId, "30123456",
                T0, guardId, gateId);
        AccessLog log = new AccessLog(
                logId, neighborhoodId, gateId, guardId, invitationId, lotId,
                Direction.IN, SubjectType.GUEST, true, null,
                "Maria", "30123456", 0, jti, T0, true, false, enrollClientId);
        when(enroller.enroll(any())).thenReturn(EnrollmentResult.Enrolled.guest(ge, log, "Maria"));

        SyncAccessUseCase.Report report = useCase.sync(neighborhoodId, guardId, List.of(enrollEv));

        assertThat(report.results()).hasSize(1);
        SyncAccessUseCase.EventResult r = report.results().get(0);
        assertThat(r.clientEventId()).isEqualTo(enrollClientId);
        assertThat(r.result()).isEqualTo(SyncAccessUseCase.Outcome.ENROLLED);
        assertThat(r.reason()).isNull();
        assertThat(r.logId()).isEqualTo(logId);
        assertThat(r.enrollmentId()).isEqualTo(enrollmentId);

        ArgumentCaptor<EnrollGuestUseCase.Command> captor =
                ArgumentCaptor.forClass(EnrollGuestUseCase.Command.class);
        verify(enroller).enroll(captor.capture());
        EnrollGuestUseCase.Command sent = captor.getValue();
        assertThat(sent.documentNumber()).isEqualTo("30123456");
        assertThat(sent.syncedOffline()).as("enroll diferido debe marcarse offline").isTrue();
        assertThat(sent.clientEventId()).isEqualTo(enrollClientId);
        verify(recorder, org.mockito.Mockito.never()).record(any());
    }

    @Test
    void enrollDeniedMapeaADeniedConReasonYSinEnrollmentId() {
        UUID enrollClientId = UUID.randomUUID();
        SyncAccessUseCase.Event enrollEv = new SyncAccessUseCase.Event(
                enrollClientId, "SP1.token", gateId, Direction.IN, 0, T0, SyncAccessUseCase.LocalDecision.ALLOWED, null, "99999999", null);
        UUID logId = UUID.randomUUID();
        AccessLog log = new AccessLog(
                logId, neighborhoodId, gateId, guardId, invitationId, lotId,
                Direction.IN, SubjectType.GUEST, false, DenialReason.DOCUMENT_MISMATCH,
                null, "99999999", 0, jti, T0, true, false, enrollClientId);
        when(enroller.enroll(any()))
                .thenReturn(EnrollmentResult.Denied.guest(DenialReason.DOCUMENT_MISMATCH, log));

        SyncAccessUseCase.Report report = useCase.sync(neighborhoodId, guardId, List.of(enrollEv));

        SyncAccessUseCase.EventResult r = report.results().get(0);
        assertThat(r.result()).isEqualTo(SyncAccessUseCase.Outcome.DENIED);
        assertThat(r.reason()).isEqualTo(DenialReason.DOCUMENT_MISMATCH);
        assertThat(r.enrollmentId()).isNull();
        assertThat(r.logId()).isEqualTo(logId);
    }

    @Test
    void batchMixtoAccessYEnrollRuteaCadaUnoALoSuyo() {
        SyncAccessUseCase.Event access = event(UUID.randomUUID(), T0);
        SyncAccessUseCase.Event enroll = new SyncAccessUseCase.Event(
                UUID.randomUUID(), "SP1.enrollTok", gateId, Direction.IN, 0, T0.plusSeconds(1),
                SyncAccessUseCase.LocalDecision.ALLOWED, null, "30123456", null);
        when(recorder.record(any())).thenReturn(allowedResult(commandFor(access), false));
        UUID enrollmentId = UUID.randomUUID();
        GuestEnrollment ge = new GuestEnrollment(
                enrollmentId, neighborhoodId, invitationId, "30123456",
                T0, guardId, gateId);
        AccessLog enrollLog = new AccessLog(
                UUID.randomUUID(), neighborhoodId, gateId, guardId, invitationId, lotId,
                Direction.IN, SubjectType.GUEST, true, null,
                "Maria", "30123456", 0, jti, T0, true, false, enroll.clientEventId());
        when(enroller.enroll(any())).thenReturn(EnrollmentResult.Enrolled.guest(ge, enrollLog, "Maria"));

        SyncAccessUseCase.Report report = useCase.sync(neighborhoodId, guardId, List.of(access, enroll));

        assertThat(report.results()).hasSize(2);
        Map<UUID, SyncAccessUseCase.EventResult> byId = report.results().stream()
                .collect(Collectors.toMap(SyncAccessUseCase.EventResult::clientEventId, Function.identity()));
        assertThat(byId.get(access.clientEventId()).result()).isEqualTo(SyncAccessUseCase.Outcome.ALLOWED);
        assertThat(byId.get(access.clientEventId()).enrollmentId()).isNull();
        assertThat(byId.get(enroll.clientEventId()).result()).isEqualTo(SyncAccessUseCase.Outcome.ENROLLED);
        assertThat(byId.get(enroll.clientEventId()).enrollmentId()).isEqualTo(enrollmentId);
        verify(recorder, org.mockito.Mockito.times(1)).record(any());
        verify(enroller, org.mockito.Mockito.times(1)).enroll(any());
    }

    private static org.mockito.ArgumentMatcher<RecordAccessAttemptUseCase.Command> withClient(UUID id) {
        return c -> c != null && id.equals(c.clientEventId());
    }

    private static RecordAccessAttemptUseCase.Command argWithClient(UUID id) {
        return org.mockito.ArgumentMatchers.argThat(withClient(id));
    }

    private SyncAccessUseCase.Event event(UUID clientEventId, Instant capturedAt) {
        return new SyncAccessUseCase.Event(
                clientEventId, "SP1.token", gateId, Direction.IN, 0, capturedAt, SyncAccessUseCase.LocalDecision.ALLOWED, null, null, null);
    }

    private RecordAccessAttemptUseCase.Command commandFor(SyncAccessUseCase.Event ev) {
        return new RecordAccessAttemptUseCase.Command(
                neighborhoodId, ev.gateId(), guardId, ev.token(), ev.direction(),
                ev.companions(), ev.capturedAt(), ev.clientEventId(), true, null);
    }

    private RecordAccessAttemptUseCase.Result allowedResult(RecordAccessAttemptUseCase.Command cmd, boolean skew) {
        AccessDecision.Allowed allowed = new AccessDecision.Allowed(
                invitationId, jti, "Maria", "M4-L12", cmd.companions());
        AccessLog log = new AccessLog(
                UUID.randomUUID(), neighborhoodId, cmd.gateId(), guardId,
                invitationId, lotId, cmd.direction(), SubjectType.GUEST,
                true, null, "Maria", "30123456", cmd.companions(),
                jti, cmd.capturedAt(), true, skew, cmd.clientEventId());
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
