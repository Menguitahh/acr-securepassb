package com.securepass.modules.invitation.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SigningKeyTest {

    private static byte[] pub() {
        return new byte[32];
    }

    private static SigningKey valid() {
        return new SigningKey(UUID.randomUUID(), UUID.randomUUID(), "abcd1234",
                pub(), "enc", true, Instant.now(), null);
    }

    @Test
    void construyeValido() {
        assertThat(valid().active()).isTrue();
    }

    @Test
    void rechazaSinId() {
        assertThatThrownBy(() -> new SigningKey(null, UUID.randomUUID(), "kid", pub(), "enc", true, Instant.now(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rechazaKidLargo() {
        String tooLong = "a".repeat(17);
        assertThatThrownBy(() -> new SigningKey(UUID.randomUUID(), UUID.randomUUID(), tooLong, pub(), "enc", true, Instant.now(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rechazaPublicKeyDeLongitudDistinta() {
        assertThatThrownBy(() -> new SigningKey(UUID.randomUUID(), UUID.randomUUID(), "k", new byte[31], "enc", true, Instant.now(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rechazaPrivateEncVacio() {
        assertThatThrownBy(() -> new SigningKey(UUID.randomUUID(), UUID.randomUUID(), "k", pub(), " ", true, Instant.now(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
