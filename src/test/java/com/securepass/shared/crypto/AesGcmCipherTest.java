package com.securepass.shared.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmCipherTest {

    private static final String TEST_KEY_B64 = "REVWX09OTFlfS0VZXzMyX0JZVEVTX1hYWFhYWFhYWFg=";

    private AesGcmCipher cipher;

    @BeforeEach
    void setUp() {
        cipher = new AesGcmCipher(new MasterKey(TEST_KEY_B64));
    }

    @Test
    void encryptDecryptRoundTrip() {
        byte[] plaintext = "clave-privada-super-secreta".getBytes();
        String ct = cipher.encrypt(plaintext);
        assertThat(cipher.decrypt(ct)).isEqualTo(plaintext);
    }

    @Test
    void dosCifradosDelMismoPlaintextProducenCiphertextDistintos() {
        byte[] plaintext = "misma-data".getBytes();
        String c1 = cipher.encrypt(plaintext);
        String c2 = cipher.encrypt(plaintext);
        assertThat(c1).isNotEqualTo(c2);
    }

    @Test
    void ivPrimerosBytesCambianEntreCifrados() {
        byte[] p = "algo".getBytes();
        byte[] first = Base64.getDecoder().decode(cipher.encrypt(p));
        byte[] second = Base64.getDecoder().decode(cipher.encrypt(p));
        byte[] iv1 = new byte[12];
        byte[] iv2 = new byte[12];
        System.arraycopy(first, 0, iv1, 0, 12);
        System.arraycopy(second, 0, iv2, 0, 12);
        assertThat(iv1).isNotEqualTo(iv2);
    }

    @Test
    void ciphertextTampereadoFalla() {
        String ct = cipher.encrypt("data".getBytes());
        byte[] raw = Base64.getDecoder().decode(ct);
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tampereo");
    }

    @Test
    void ciphertextConDistintaMasterKeyFalla() {
        String ct = cipher.encrypt("data".getBytes());
        AesGcmCipher otro = new AesGcmCipher(new MasterKey("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="));
        assertThatThrownBy(() -> otro.decrypt(ct)).isInstanceOf(IllegalStateException.class);
    }
}
