package com.securepass.modules.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ActivationTokenTest {

    private static byte[] hash32() {
        byte[] h = new byte[32];
        for (int i = 0; i < 32; i++) h[i] = (byte) i;
        return h;
    }

    @Test
    void aceptaValoresValidos() {
        Instant now = Instant.parse("2026-08-24T12:00:00Z");
        ActivationToken t = new ActivationToken(UUID.randomUUID(), UUID.randomUUID(),
                TokenPurpose.ACTIVATION, hash32(), now.plusSeconds(3600), null, now);
        assertFalse(t.isConsumed());
        assertFalse(t.isExpired(now));
        assertTrue(t.isUsable(now));
    }

    @Test
    void hashDebeSer32Bytes() {
        Instant now = Instant.parse("2026-08-24T12:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> new ActivationToken(
                UUID.randomUUID(), UUID.randomUUID(), TokenPurpose.ACTIVATION,
                new byte[16], now.plusSeconds(60), null, now));
    }

    @Test
    void expiresDebeSerPosteriorACreatedAt() {
        Instant now = Instant.parse("2026-08-24T12:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> new ActivationToken(
                UUID.randomUUID(), UUID.randomUUID(), TokenPurpose.ACTIVATION,
                hash32(), now, null, now));
    }

    @Test
    void detectaConsumidoYVencido() {
        Instant now = Instant.parse("2026-08-24T12:00:00Z");
        ActivationToken vencido = new ActivationToken(UUID.randomUUID(), UUID.randomUUID(),
                TokenPurpose.PASSWORD_RESET, hash32(), now.plusSeconds(1), null, now);
        assertTrue(vencido.isExpired(now.plusSeconds(2)));
        assertFalse(vencido.isUsable(now.plusSeconds(2)));

        ActivationToken consumido = new ActivationToken(UUID.randomUUID(), UUID.randomUUID(),
                TokenPurpose.ACTIVATION, hash32(), now.plusSeconds(3600), now.plusSeconds(10), now);
        assertTrue(consumido.isConsumed());
        assertFalse(consumido.isUsable(now.plusSeconds(20)));
    }

    @Test
    void tokenHashSeClonaAlEntrarYSalir() {
        Instant now = Instant.parse("2026-08-24T12:00:00Z");
        byte[] input = hash32();
        ActivationToken t = new ActivationToken(UUID.randomUUID(), UUID.randomUUID(),
                TokenPurpose.ACTIVATION, input, now.plusSeconds(3600), null, now);
        input[0] = 99;
        assertNotEquals(99, t.tokenHash()[0]);

        byte[] out = t.tokenHash();
        out[1] = 99;
        assertNotEquals(99, t.tokenHash()[1]);
    }
}
