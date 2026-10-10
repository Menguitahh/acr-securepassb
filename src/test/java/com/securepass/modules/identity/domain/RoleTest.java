package com.securepass.modules.identity.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** securepassb#256: la tabla guarda el rol con prefijo ROLE_; la API, sin prefijo. */
class RoleTest {

    @Test
    void idaYVueltaConElPrefijo() {
        for (Role role : Role.values()) {
            assertThat(role.authority()).isEqualTo("ROLE_" + role.name());
            assertThat(Role.fromAuthority(role.authority())).isEqualTo(role);
        }
    }

    @Test
    void unValorSinPrefijoEsUnError() {
        assertThatThrownBy(() -> Role.fromAuthority("ADMIN")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Role.fromAuthority(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
