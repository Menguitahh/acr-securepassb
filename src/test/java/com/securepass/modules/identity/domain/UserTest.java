package com.securepass.modules.identity.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserTest {

    private static User validUser() {
        return new User(
                UUID.randomUUID(),
                "juan@mail.com",
                null,
                "hash",
                "Juan Perez",
                "30123456", null,
                UserStatus.ACTIVE
        );
    }

    @Test
    void construyeConEmail() {
        User u = validUser();
        assertThat(u.isActive()).isTrue();
    }

    @Test
    void construyeConPhoneSinEmail() {
        User u = new User(UUID.randomUUID(), null, "+5492611111111", "hash", "Ana", null, null, UserStatus.ACTIVE);
        assertThat(u.phone()).isNotBlank();
    }

    @Test
    void rechazaSinEmailNiPhone() {
        assertThatThrownBy(() -> new User(UUID.randomUUID(), null, null, "hash", "Ana", null, null, UserStatus.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rechazaEmailInvalido() {
        assertThatThrownBy(() -> new User(UUID.randomUUID(), "no-email", null, "hash", "Ana", null, null, UserStatus.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rechazaSinPasswordHash() {
        assertThatThrownBy(() -> new User(UUID.randomUUID(), "a@b.com", null, " ", "Ana", null, null, UserStatus.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rechazaSinFullName() {
        assertThatThrownBy(() -> new User(UUID.randomUUID(), "a@b.com", null, "hash", " ", null, null, UserStatus.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rechazaFullNameLargo() {
        String largo = "a".repeat(141);
        assertThatThrownBy(() -> new User(UUID.randomUUID(), "a@b.com", null, "hash", largo, null, null, UserStatus.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rechazaSinId() {
        assertThatThrownBy(() -> new User(null, "a@b.com", null, "hash", "Ana", null, null, UserStatus.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rechazaSinStatus() {
        assertThatThrownBy(() -> new User(UUID.randomUUID(), "a@b.com", null, "hash", "Ana", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isActiveFalseSiSuspendido() {
        User u = new User(UUID.randomUUID(), "a@b.com", null, "hash", "Ana", null, null, UserStatus.SUSPENDED);
        assertThat(u.isActive()).isFalse();
    }
}
