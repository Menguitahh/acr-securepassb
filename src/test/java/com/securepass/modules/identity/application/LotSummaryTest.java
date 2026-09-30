package com.securepass.modules.identity.application;

import com.securepass.shared.schedule.ScheduleWindow;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LotSummaryTest {

    private static final ScheduleWindow FRANJA = new ScheduleWindow(List.of(1), LocalTime.of(8, 0), LocalTime.of(12, 0));

    @Test
    void workerSinServicioOFranjaEsInvalido() {
        assertThatThrownBy(() -> LotSummary.worker(UUID.randomUUID(), "A-1", " ", FRANJA))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LotSummary.worker(UUID.randomUUID(), "A-1", "Jardineria", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void workerNoInvitaNiEsTitular() {
        assertThatThrownBy(() -> new LotSummary(UUID.randomUUID(), "A-1", true, false,
                LotSummary.Kind.WORKER, "Jardineria", FRANJA))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void residenteNoLlevaServicioNiFranja() {
        assertThatThrownBy(() -> new LotSummary(UUID.randomUUID(), "A-1", true, true,
                LotSummary.Kind.OWNER, "Jardineria", FRANJA))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void elJwtDejaAfueraLosLotesWorker() {
        UUID propio = UUID.randomUUID();
        UserProfileSummary summary = new UserProfileSummary(null, null, List.of(), List.of(
                LotSummary.resident(propio, "A-1", true, true),
                LotSummary.worker(UUID.randomUUID(), "A-14", "Jardineria", FRANJA)));

        assertThat(summary.residentLotIds()).containsExactly(propio);
    }
}
