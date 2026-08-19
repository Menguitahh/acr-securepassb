package com.securepass.shared.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Ed25519KeyFactoryTest {

    @Test
    void generaClavesDe32Bytes() {
        Ed25519KeyPair pair = Ed25519KeyFactory.generate();
        assertThat(pair.publicKey()).hasSize(32);
        assertThat(pair.privateKey()).hasSize(32);
    }

    @Test
    void dosGeneracionesProducenClavesDistintas() {
        Ed25519KeyPair a = Ed25519KeyFactory.generate();
        Ed25519KeyPair b = Ed25519KeyFactory.generate();
        assertThat(a.publicKey()).isNotEqualTo(b.publicKey());
        assertThat(a.privateKey()).isNotEqualTo(b.privateKey());
    }
}
