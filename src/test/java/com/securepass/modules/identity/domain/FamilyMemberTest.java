package com.securepass.modules.identity.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FamilyMemberTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID lotId = UUID.randomUUID();

    @Test
    void miembroValidoSinRelation() {
        assertThatCode(() -> new FamilyMember(userId, lotId, null, false, true, true))
                .doesNotThrowAnyException();
    }

    @Test
    void idsRequeridos() {
        assertThatThrownBy(() -> new FamilyMember(null, lotId, null, false, true, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FamilyMember(userId, null, null, false, true, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void titularNoPuedeEstarInactivo() {
        assertThatThrownBy(() -> new FamilyMember(userId, lotId, null, true, true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("titular no puede estar inactivo");
    }

    @Test
    void relationTieneTopeDe30() {
        assertThatThrownBy(() -> new FamilyMember(
                userId, lotId, "r".repeat(31), false, true, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relation excede");
    }
}
