package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.AccessLog;
import com.securepass.modules.access.domain.Direction;
import com.securepass.modules.access.domain.SubjectType;
import com.securepass.shared.exception.StateConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManualAccessUseCaseTest {

    private OpenShiftQuery shifts;
    private AccessLogRepository logs;
    private ManualAccessUseCase useCase;
    private UUID neighborhoodId;
    private UUID gateId;
    private UUID guardId;
    private UUID lotId;
    private UUID clientEventId;

    private LotStatusQuery lotBarrio;

    @BeforeEach
    void setUp() {
        shifts = mock(OpenShiftQuery.class);
        lotBarrio = mock(LotStatusQuery.class);
        logs = mock(AccessLogRepository.class);
        useCase = new ManualAccessUseCase(shifts, lotBarrio, logs);
        neighborhoodId = UUID.randomUUID();
        gateId = UUID.randomUUID();
        guardId = UUID.randomUUID();
        lotId = UUID.randomUUID();
        clientEventId = UUID.randomUUID();
        when(lotBarrio.findNeighborhoodId(lotId)).thenReturn(Optional.of(neighborhoodId));
        when(logs.saveIdempotent(any(AccessLog.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ManualAccessUseCase.Command cmd() {
        return new ManualAccessUseCase.Command(
                neighborhoodId, gateId, guardId, lotId,
                "Juan Perez", "30123456", clientEventId, Instant.now());
    }

    @Test
    void registraIngresoManualConTurnoAbierto() {
        when(shifts.hasOpenShift(guardId)).thenReturn(true);
        when(logs.findByClientEventId(clientEventId)).thenReturn(Optional.empty());

        AccessLog log = useCase.record(cmd());

        assertThat(log.direction()).isEqualTo(Direction.IN);
        assertThat(log.subjectType()).isEqualTo(SubjectType.MANUAL);
        assertThat(log.allowed()).isTrue();
        assertThat(log.invitationId()).isNull();
        assertThat(log.lotId()).isEqualTo(lotId);
        assertThat(log.subjectName()).isEqualTo("Juan Perez");
    }

    // Sin turno abierto rechaza (BR-09 lado consumidor).
    @Test
    void sinTurnoAbiertoEs409() {
        when(logs.findByClientEventId(clientEventId)).thenReturn(Optional.empty());
        when(shifts.hasOpenShift(guardId)).thenReturn(false);

        assertThatThrownBy(() -> useCase.record(cmd()))
                .isInstanceOf(StateConflictException.class);
        verify(logs, never()).saveIdempotent(any());
    }

    @Test
    void mismoClientEventIdReconstruyeElLogPrevio() {
        AccessLog previous = new AccessLog(
                UUID.randomUUID(), neighborhoodId, gateId, guardId, null, lotId,
                Direction.IN, SubjectType.MANUAL, true, null,
                "Juan Perez", "30123456", 0, null, Instant.now(), false, false, clientEventId);
        when(logs.findByClientEventId(clientEventId)).thenReturn(Optional.of(previous));

        AccessLog result = useCase.record(cmd());

        assertThat(result).isSameAs(previous);
        verify(shifts, never()).hasOpenShift(any());
        verify(logs, never()).saveIdempotent(any());
    }
}
