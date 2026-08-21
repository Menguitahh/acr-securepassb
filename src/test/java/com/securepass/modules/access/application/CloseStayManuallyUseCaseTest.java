package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.AccessLog;
import com.securepass.modules.access.domain.Direction;
import com.securepass.modules.access.domain.Stay;
import com.securepass.modules.access.domain.SubjectType;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.exception.StateConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

class CloseStayManuallyUseCaseTest {

    private static final Instant ENTER = Instant.parse("2026-08-20T15:00:00Z");

    private StayRepository stays;
    private AccessLogRepository logs;
    private CloseStayManuallyUseCase useCase;
    private UUID neighborhoodId;
    private UUID gateId;
    private UUID guardId;
    private UUID stayId;
    private UUID invitationId;
    private UUID lotId;

    @BeforeEach
    void setUp() {
        stays = mock(StayRepository.class);
        logs = mock(AccessLogRepository.class);
        useCase = new CloseStayManuallyUseCase(stays, logs);
        neighborhoodId = UUID.randomUUID();
        gateId = UUID.randomUUID();
        guardId = UUID.randomUUID();
        stayId = UUID.randomUUID();
        invitationId = UUID.randomUUID();
        lotId = UUID.randomUUID();
        when(logs.saveIdempotent(any(AccessLog.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Stay openStay() {
        return new Stay(
                stayId, neighborhoodId, invitationId, lotId,
                "Maria Lopez", "30123456", 2,
                UUID.randomUUID(), null, ENTER, null,
                ENTER.plusSeconds(3600), false, null);
    }

    // US-39: cierre manual marca la stay y emite egreso MANUAL en la bitacora.
    @Test
    void cierreManualCierraStayYEmiteAccessLogManual() {
        when(stays.findById(stayId)).thenReturn(Optional.of(openStay()));

        Stay closed = useCase.close(neighborhoodId, gateId, guardId, stayId);

        assertThat(closed.closedManually()).isTrue();
        assertThat(closed.closedBy()).isEqualTo(guardId);
        assertThat(closed.exitedAt()).isNotNull();

        ArgumentCaptor<AccessLog> captor = ArgumentCaptor.forClass(AccessLog.class);
        verify(logs).saveIdempotent(captor.capture());
        AccessLog synthetic = captor.getValue();
        assertThat(synthetic.direction()).isEqualTo(Direction.OUT);
        assertThat(synthetic.subjectType()).isEqualTo(SubjectType.MANUAL);
        assertThat(synthetic.allowed()).isTrue();
        assertThat(synthetic.invitationId()).isEqualTo(invitationId);
        assertThat(synthetic.lotId()).isEqualTo(lotId);
        assertThat(synthetic.guardId()).isEqualTo(guardId);

        verify(stays).close(closed);
    }

    @Test
    void rechazaCerrarStayYaCerrada() {
        Stay closedStay = openStay().close(UUID.randomUUID(), ENTER.plusSeconds(60), false, null);
        when(stays.findById(stayId)).thenReturn(Optional.of(closedStay));

        assertThatThrownBy(() -> useCase.close(neighborhoodId, gateId, guardId, stayId))
                .isInstanceOf(StateConflictException.class);
        verify(logs, never()).saveIdempotent(any());
        verify(stays, never()).close(any());
    }

    @Test
    void stayInexistenteEs404() {
        when(stays.findById(stayId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.close(neighborhoodId, gateId, guardId, stayId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void stayDeOtroBarrioEs404() {
        Stay foreign = new Stay(
                stayId, UUID.randomUUID(), invitationId, lotId,
                "Maria Lopez", "30123456", 2,
                UUID.randomUUID(), null, ENTER, null,
                ENTER.plusSeconds(3600), false, null);
        when(stays.findById(stayId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> useCase.close(neighborhoodId, gateId, guardId, stayId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(logs, never()).saveIdempotent(any());
    }
}
