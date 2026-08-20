package com.securepass.modules.neighborhood.application;

import com.securepass.modules.neighborhood.domain.Lot;
import com.securepass.modules.neighborhood.domain.LotStatus;
import com.securepass.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpdateLotUseCaseTest {

    private final UUID neighborhood = UUID.randomUUID();
    private LotRepository lots;
    private UpdateLotUseCase useCase;

    @BeforeEach
    void setUp() {
        lots = mock(LotRepository.class);
        useCase = new UpdateLotUseCase(lots);
    }

    @Test
    void suspende_lote_para_aplicar_br03() {
        UUID lotId = UUID.randomUUID();
        Instant now = Instant.now();
        Lot lot = new Lot(lotId, neighborhood, "M4", "C", "1", LotStatus.ACTIVE, now, now);
        when(lots.findById(lotId)).thenReturn(Optional.of(lot));

        Lot updated = useCase.update(neighborhood, lotId, new UpdateLotUseCase.Patch(LotStatus.SUSPENDED, null, null));

        assertThat(updated.status()).isEqualTo(LotStatus.SUSPENDED);
        verify(lots).update(updated);
    }

    @Test
    void notFound_si_el_lote_no_es_del_barrio() {
        UUID lotId = UUID.randomUUID();
        Instant now = Instant.now();
        Lot lot = new Lot(lotId, UUID.randomUUID(), "M4", null, null, LotStatus.ACTIVE, now, now);
        when(lots.findById(lotId)).thenReturn(Optional.of(lot));

        assertThatThrownBy(() -> useCase.update(neighborhood, lotId, new UpdateLotUseCase.Patch(LotStatus.SUSPENDED, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
