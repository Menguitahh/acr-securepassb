package com.securepass.modules.neighborhood.application;

import com.securepass.modules.access.application.OpenShiftQuery;
import com.securepass.modules.neighborhood.domain.Lot;
import com.securepass.modules.neighborhood.domain.LotStatus;
import com.securepass.shared.exception.StateConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchLotsForGuardUseCaseTest {

    private LotRepository lots;
    private OpenShiftQuery shifts;
    private SearchLotsForGuardUseCase useCase;
    private UUID neighborhoodId;
    private UUID guardId;

    @BeforeEach
    void setUp() {
        lots = mock(LotRepository.class);
        shifts = mock(OpenShiftQuery.class);
        useCase = new SearchLotsForGuardUseCase(lots, shifts);
        neighborhoodId = UUID.randomUUID();
        guardId = UUID.randomUUID();
    }

    private Lot lot(String code) {
        return new Lot(UUID.randomUUID(), neighborhoodId, code, "Calle", "1",
                LotStatus.ACTIVE, Instant.now(), Instant.now());
    }

    @Test
    void devuelveResultadosCuandoHayTurnoAbierto() {
        when(shifts.hasOpenShift(guardId)).thenReturn(true);
        List<Lot> expected = List.of(lot("M4-L12"), lot("M4-L13"));
        when(lots.searchActiveByCodePrefix(eq(neighborhoodId), eq("M4"), eq(10))).thenReturn(expected);

        List<Lot> result = useCase.search(neighborhoodId, guardId, "M4");

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void recortaEspaciosAlrededorDelQuery() {
        when(shifts.hasOpenShift(guardId)).thenReturn(true);
        when(lots.searchActiveByCodePrefix(eq(neighborhoodId), eq("A"), anyInt())).thenReturn(List.of());

        useCase.search(neighborhoodId, guardId, "  A  ");

        verify(lots).searchActiveByCodePrefix(neighborhoodId, "A", 10);
    }

    // BR-09: sin turno abierto no puede consultar (mismo criterio que ManualAccess).
    @Test
    void sinTurnoAbiertoEs409() {
        when(shifts.hasOpenShift(guardId)).thenReturn(false);

        assertThatThrownBy(() -> useCase.search(neighborhoodId, guardId, "M4"))
                .isInstanceOf(StateConflictException.class);
        verify(lots, never()).searchActiveByCodePrefix(any(), any(), anyInt());
    }

    @Test
    void queryVacioRechazado() {
        assertThatThrownBy(() -> useCase.search(neighborhoodId, guardId, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void argumentosNulosRechazados() {
        assertThatThrownBy(() -> useCase.search(null, guardId, "M4"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.search(neighborhoodId, null, "M4"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.search(neighborhoodId, guardId, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
