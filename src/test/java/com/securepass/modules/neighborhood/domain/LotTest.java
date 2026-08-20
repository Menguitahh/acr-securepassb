package com.securepass.modules.neighborhood.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LotTest {

    private static final UUID N = UUID.randomUUID();
    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    private Lot make(String code) {
        return new Lot(UUID.randomUUID(), N, code, "Calle", "1", LotStatus.ACTIVE, T, T);
    }

    @Test
    void code_requerido() {
        assertThatThrownBy(() -> make(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> make(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void code_no_excede_30_caracteres() {
        String tooLong = "X".repeat(31);
        assertThatThrownBy(() -> make(tooLong)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withStatus_actualiza_updatedAt() {
        Lot lot = make("M4-L12");
        Instant later = T.plusSeconds(60);
        Lot suspended = lot.withStatus(LotStatus.SUSPENDED, later);
        assertThat(suspended.status()).isEqualTo(LotStatus.SUSPENDED);
        assertThat(suspended.updatedAt()).isEqualTo(later);
        assertThat(suspended.createdAt()).isEqualTo(T);
    }

    @Test
    void withStatus_igual_es_noop() {
        Lot lot = make("M4-L12");
        Lot same = lot.withStatus(LotStatus.ACTIVE, T.plusSeconds(60));
        assertThat(same).isSameAs(lot);
    }
}
