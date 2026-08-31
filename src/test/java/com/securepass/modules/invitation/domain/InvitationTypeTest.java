package com.securepass.modules.invitation.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvitationTypeTest {

    @Test
    void codigosDeUnaLetra() {
        assertThat(InvitationType.SINGLE.code()).isEqualTo("S");
        assertThat(InvitationType.EVENT.code()).isEqualTo("E");
    }

    @Test
    void fromCodeRoundTrip() {
        for (InvitationType t : InvitationType.values()) {
            assertThat(InvitationType.fromCode(t.code())).isEqualTo(t);
        }
    }

    @Test
    void fromCodeDesconocidoFalla() {
        assertThatThrownBy(() -> InvitationType.fromCode("X"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
