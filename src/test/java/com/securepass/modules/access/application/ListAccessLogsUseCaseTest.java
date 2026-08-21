package com.securepass.modules.access.application;

import com.securepass.modules.identity.domain.Role;
import com.securepass.shared.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ListAccessLogsUseCaseTest {

    private AccessLogQuery query;
    private ListAccessLogsUseCase useCase;
    private UUID neighborhoodId;
    private UUID lot1;
    private UUID lot2;

    @BeforeEach
    void setUp() {
        query = mock(AccessLogQuery.class);
        useCase = new ListAccessLogsUseCase(query);
        neighborhoodId = UUID.randomUUID();
        lot1 = UUID.randomUUID();
        lot2 = UUID.randomUUID();
        when(query.list(any(AccessLogQuery.Filter.class)))
                .thenReturn(new AccessLogQuery.Page(List.of(), 0, 25, 0));
    }

    // Aislamiento US-26: residente ve solo sus lotes; el filtro se pasa al
    // adapter como lotIdsAllowed.
    @Test
    void residenteRestringeALotesPropios() {
        CurrentUser resident = new CurrentUser(
                UUID.randomUUID(), neighborhoodId, List.of(Role.RESIDENT_MEMBER), List.of(lot1, lot2));

        useCase.list(resident, null, null, null, null, 0);

        ArgumentCaptor<AccessLogQuery.Filter> captor = ArgumentCaptor.forClass(AccessLogQuery.Filter.class);
        verify(query).list(captor.capture());
        assertThat(captor.getValue().lotIdsAllowed()).containsExactlyInAnyOrder(lot1, lot2);
        assertThat(captor.getValue().neighborhoodId()).isEqualTo(neighborhoodId);
    }

    @Test
    void residenteConLotFilterFueraDeSuAlcanceEs403() {
        CurrentUser resident = new CurrentUser(
                UUID.randomUUID(), neighborhoodId, List.of(Role.RESIDENT_OWNER), List.of(lot1));
        UUID otro = UUID.randomUUID();

        assertThatThrownBy(() -> useCase.list(resident, null, null, otro, null, 0))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void adminNoLimitaLotes() {
        CurrentUser admin = new CurrentUser(
                UUID.randomUUID(), neighborhoodId, List.of(Role.ADMIN), List.of());

        useCase.list(admin, null, null, null, null, 0);

        ArgumentCaptor<AccessLogQuery.Filter> captor = ArgumentCaptor.forClass(AccessLogQuery.Filter.class);
        verify(query).list(captor.capture());
        assertThat(captor.getValue().lotIdsAllowed()).isEmpty();
    }

    // Ventana por defecto de 90 dias cuando no se pasa from.
    @Test
    void ventanaPorDefectoDe90Dias() {
        CurrentUser admin = new CurrentUser(
                UUID.randomUUID(), neighborhoodId, List.of(Role.ADMIN), List.of());

        useCase.list(admin, null, null, null, null, 0);

        ArgumentCaptor<AccessLogQuery.Filter> captor = ArgumentCaptor.forClass(AccessLogQuery.Filter.class);
        verify(query).list(captor.capture());
        assertThat(captor.getValue().from()).isNotNull();
        long days = java.time.Duration.between(captor.getValue().from(), Instant.now()).toDays();
        assertThat(days).isBetween(89L, 91L);
    }

    @Test
    void filtroResultAllowedSePropaga() {
        CurrentUser admin = new CurrentUser(
                UUID.randomUUID(), neighborhoodId, List.of(Role.ADMIN), List.of());

        useCase.list(admin, null, null, null, Boolean.TRUE, 0);

        ArgumentCaptor<AccessLogQuery.Filter> captor = ArgumentCaptor.forClass(AccessLogQuery.Filter.class);
        verify(query).list(captor.capture());
        assertThat(captor.getValue().allowedOnly()).isTrue();
    }
}
