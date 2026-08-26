package com.securepass.modules.access.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ListOpenStaysUseCaseTest {

    // Aislamiento: la consulta pasa el barrio del actor; el JDBC filtra por
    // `neighborhood_id`. Si el use case olvidara el filtro, un GUARD veria
    // stays de otros barrios.
    @Test
    void filtraPorElBarrioDelActor() {
        StayRepository repo = mock(StayRepository.class);
        UUID mine = UUID.randomUUID();
        OpenStayView view = new OpenStayView(
                UUID.randomUUID(), UUID.randomUUID(), "Maria Lopez", "M4-L12",
                Instant.parse("2026-08-20T15:00:00Z"),
                Instant.parse("2026-08-20T23:00:00Z"), false, null);
        when(repo.listOpenByNeighborhood(mine)).thenReturn(List.of(view));

        List<OpenStayView> result = new ListOpenStaysUseCase(repo).list(mine);

        assertThat(result).containsExactly(view);
        verify(repo).listOpenByNeighborhood(mine);
    }

    @Test
    void neighborhoodIdRequerido() {
        StayRepository repo = mock(StayRepository.class);
        assertThatThrownBy(() -> new ListOpenStaysUseCase(repo).list(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
