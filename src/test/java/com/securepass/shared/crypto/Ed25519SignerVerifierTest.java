package com.securepass.shared.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Ed25519SignerVerifierTest {

    @Test
    void firmaYVerificaRoundTrip() {
        Ed25519KeyPair pair = Ed25519KeyFactory.generate();
        byte[] message = "hola mundo".getBytes();
        byte[] signature = Ed25519Signer.sign(pair.privateKey(), message);

        assertThat(signature).hasSize(64);
        assertThat(Ed25519Verifier.verify(pair.publicKey(), message, signature)).isTrue();
    }

    @Test
    void mensajeTampereadoFallaVerificacion() {
        Ed25519KeyPair pair = Ed25519KeyFactory.generate();
        byte[] message = "original".getBytes();
        byte[] signature = Ed25519Signer.sign(pair.privateKey(), message);

        byte[] tampered = "originaX".getBytes();
        assertThat(Ed25519Verifier.verify(pair.publicKey(), tampered, signature)).isFalse();
    }

    @Test
    void firmaTampereadaFallaVerificacion() {
        Ed25519KeyPair pair = Ed25519KeyFactory.generate();
        byte[] message = "mensaje".getBytes();
        byte[] signature = Ed25519Signer.sign(pair.privateKey(), message);
        signature[0] ^= 0x01;

        assertThat(Ed25519Verifier.verify(pair.publicKey(), message, signature)).isFalse();
    }

    @Test
    void clavePublicaDistintaFallaVerificacion() {
        Ed25519KeyPair a = Ed25519KeyFactory.generate();
        Ed25519KeyPair b = Ed25519KeyFactory.generate();
        byte[] message = "mensaje".getBytes();
        byte[] signature = Ed25519Signer.sign(a.privateKey(), message);

        assertThat(Ed25519Verifier.verify(b.publicKey(), message, signature)).isFalse();
    }
}
