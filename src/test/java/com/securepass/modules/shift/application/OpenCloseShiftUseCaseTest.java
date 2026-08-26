package com.securepass.modules.shift.application;

import com.securepass.modules.shift.domain.Shift;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenCloseShiftUseCaseTest {

    private ShiftRepository shifts;
    private OpenShiftUseCase openUseCase;
    private CloseShiftUseCase closeUseCase;
    private UUID neighborhoodId;
    private UUID gateId;
    private UUID guardId;

    @BeforeEach
    void setUp() {
        shifts = mock(ShiftRepository.class);
        openUseCase = new OpenShiftUseCase(shifts);
        closeUseCase = new CloseShiftUseCase(shifts);
        neighborhoodId = UUID.randomUUID();
        gateId = UUID.randomUUID();
        guardId = UUID.randomUUID();
        when(shifts.openIfNotOpen(any(Shift.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void abreTurnoNuevo() {
        Shift shift = openUseCase.open(neighborhoodId, gateId, guardId, "sin novedades");
        assertThat(shift.isOpen()).isTrue();
        assertThat(shift.guardId()).isEqualTo(guardId);
        verify(shifts).openIfNotOpen(shift);
    }

    // BR-09: dos turnos abiertos del mismo guardia = 409 (index unique).
    @Test
    void br09SegundaAperturaEs409() {
        when(shifts.openIfNotOpen(any(Shift.class)))
                .thenThrow(new StateConflictException("el guardia ya tiene un turno abierto"));

        assertThatThrownBy(() -> openUseCase.open(neighborhoodId, gateId, guardId, null))
                .isInstanceOf(StateConflictException.class);
    }

    @Test
    void cierraTurnoAbiertoActualizandoNotas() {
        Shift open = new Shift(
                UUID.randomUUID(), neighborhoodId, gateId, guardId,
                Instant.now().minusSeconds(60), null, "notas viejas");
        when(shifts.findOpenByGuard(guardId)).thenReturn(Optional.of(open));

        Shift closed = closeUseCase.close(guardId, "entrega OK");

        assertThat(closed.isOpen()).isFalse();
        assertThat(closed.handoverNotes()).isEqualTo("entrega OK");
        verify(shifts).close(closed);
    }

    @Test
    void cierreSinTurnoAbiertoEs409() {
        when(shifts.findOpenByGuard(guardId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> closeUseCase.close(guardId, null))
                .isInstanceOf(StateConflictException.class);
    }
}
