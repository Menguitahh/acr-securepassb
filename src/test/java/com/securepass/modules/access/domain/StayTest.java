package com.securepass.modules.access.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StayTest {

    private static final Instant ENTER = Instant.parse("2026-08-20T15:00:00Z");
    private static final Instant EXPECTED = ENTER.plusSeconds(3600);
    private static final UUID NEIGHBORHOOD = UUID.randomUUID();
    private static final UUID INVITATION = UUID.randomUUID();
    private static final UUID LOT = UUID.randomUUID();
    private static final UUID ENTRY_LOG = UUID.randomUUID();

    private Stay openStay() {
        return new Stay(
                UUID.randomUUID(), NEIGHBORHOOD, INVITATION, LOT,
                "Maria Lopez", "30123456", 2,
                ENTRY_LOG, null, ENTER, null, EXPECTED, false, null);
    }

    @Test
    void abiertaMientrasNoTengaExitedAt() {
        assertThat(openStay().isOpen()).isTrue();
    }

    @Test
    void closeMarcaExitedAtYExitLog() {
        UUID exitLog = UUID.randomUUID();
        UUID by = UUID.randomUUID();
        Instant exit = ENTER.plusSeconds(1800);

        Stay closed = openStay().close(exitLog, exit, false, by);

        assertThat(closed.isOpen()).isFalse();
        assertThat(closed.exitLogId()).isEqualTo(exitLog);
        assertThat(closed.exitedAt()).isEqualTo(exit);
        assertThat(closed.closedManually()).isFalse();
    }

    @Test
    void closeManualRegistraFlagYActor() {
        UUID guard = UUID.randomUUID();
        Stay closed = openStay().close(UUID.randomUUID(), ENTER.plusSeconds(60), true, guard);

        assertThat(closed.closedManually()).isTrue();
        assertThat(closed.closedBy()).isEqualTo(guard);
    }

    @Test
    void closeDosVecesLanza() {
        Stay closed = openStay().close(UUID.randomUUID(), ENTER.plusSeconds(60), false, null);
        assertThatThrownBy(() -> closed.close(UUID.randomUUID(), ENTER.plusSeconds(120), false, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void exitedAntesDeEnteredEsInvalido() {
        assertThatThrownBy(() -> openStay().close(UUID.randomUUID(), ENTER.minusSeconds(60), false, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
