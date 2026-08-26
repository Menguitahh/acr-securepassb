package com.securepass.modules.identity.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MembershipTest {

    @Test
    void construyeValido() {
        Membership m = new Membership(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Role.RESIDENT_OWNER, true);
        assertThat(m.active()).isTrue();
    }

    @Test
    void rechazaSinUserId() {
        assertThatThrownBy(() -> new Membership(UUID.randomUUID(), null, UUID.randomUUID(), Role.GUARD, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rechazaSinNeighborhood() {
        assertThatThrownBy(() -> new Membership(UUID.randomUUID(), UUID.randomUUID(), null, Role.GUARD, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rechazaSinRole() {
        assertThatThrownBy(() -> new Membership(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
