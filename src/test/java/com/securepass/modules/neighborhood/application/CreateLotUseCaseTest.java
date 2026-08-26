package com.securepass.modules.neighborhood.application;

import com.securepass.modules.neighborhood.domain.Lot;
import com.securepass.modules.neighborhood.domain.LotStatus;
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

class CreateLotUseCaseTest {

    private final UUID neighborhood = UUID.randomUUID();
    private LotRepository lots;
    private CreateLotUseCase useCase;

    @BeforeEach
    void setUp() {
        lots = mock(LotRepository.class);
        useCase = new CreateLotUseCase(lots);
    }

    @Test
    void crea_lote_activo_con_codigo_unico() {
        when(lots.findByNeighborhoodAndCode(neighborhood, "M4-L12")).thenReturn(Optional.empty());

        Lot lot = useCase.create(neighborhood, new CreateLotUseCase.Command("M4-L12", "Calle", "10"));

        assertThat(lot.status()).isEqualTo(LotStatus.ACTIVE);
        assertThat(lot.code()).isEqualTo("M4-L12");
        assertThat(lot.neighborhoodId()).isEqualTo(neighborhood);
        verify(lots).save(any(Lot.class));
    }

    @Test
    void rechaza_codigo_duplicado() {
        Instant now = Instant.now();
        Lot existing = new Lot(UUID.randomUUID(), neighborhood, "M4-L12", null, null, LotStatus.ACTIVE, now, now);
        when(lots.findByNeighborhoodAndCode(neighborhood, "M4-L12")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> useCase.create(neighborhood, new CreateLotUseCase.Command("M4-L12", null, null)))
                .isInstanceOf(StateConflictException.class);
    }
}
