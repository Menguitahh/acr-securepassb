package com.securepass.modules.identity.application;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ActivationTokenGeneratorTest {

    private final ActivationTokenGenerator gen = new ActivationTokenGenerator();

    @Test
    void generaTokenBase64UrlSinPadding() {
        ActivationTokenGenerator.Generated g = gen.generate();
        assertNotNull(g.plaintext());
        assertFalse(g.plaintext().contains("="));
        assertFalse(g.plaintext().contains("+"));
        assertFalse(g.plaintext().contains("/"));
        // 32 bytes en base64 sin padding = 43 caracteres
        assertEquals(43, g.plaintext().length());
    }

    @Test
    void hashEsSha256DelPlaintext() {
        ActivationTokenGenerator.Generated g = gen.generate();
        byte[] recomputed = ActivationTokenGenerator.hash(g.plaintext());
        assertArrayEquals(recomputed, g.hash());
        assertEquals(32, g.hash().length);
    }

    @Test
    void nSeriesGeneranValoresYHashesUnicos() {
        int n = 1000;
        Set<String> plaintexts = new HashSet<>(n);
        Set<String> hashes = new HashSet<>(n);
        for (int i = 0; i < n; i++) {
            ActivationTokenGenerator.Generated g = gen.generate();
            plaintexts.add(g.plaintext());
            hashes.add(Arrays.toString(g.hash()));
        }
        assertEquals(n, plaintexts.size());
        assertEquals(n, hashes.size());
    }

    @Test
    void hashRechazaEntradaVacia() {
        assertThrows(IllegalArgumentException.class, () -> ActivationTokenGenerator.hash(""));
        assertThrows(IllegalArgumentException.class, () -> ActivationTokenGenerator.hash(null));
    }
}
