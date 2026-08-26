package com.securepass.shared.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MasterKeyTest {

    private static final String VALID = "REVWX09OTFlfS0VZXzMyX0JZVEVTX1hYWFhYWFhYWFg=";

    @Test
    void aceptaClaveValida() {
        assertThat(new MasterKey(VALID).bytes()).hasSize(32);
    }

    @Test
    void faltanteFallaConMensajeClaro() {
        assertThatThrownBy(() -> new MasterKey(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SECUREPASS_MASTER_KEY");
    }

    @Test
    void nullFallaConMensajeClaro() {
        assertThatThrownBy(() -> new MasterKey(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SECUREPASS_MASTER_KEY");
    }

    @Test
    void base64InvalidoFalla() {
        assertThatThrownBy(() -> new MasterKey("###no-base64###"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
    }

    @Test
    void longitudDistinta32BytesFalla() {
        String tooShort = "c2hvcnQ=";
        assertThatThrownBy(() -> new MasterKey(tooShort))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void bytesEsCopiaDefensiva() {
        MasterKey key = new MasterKey(VALID);
        byte[] first = key.bytes();
        first[0] = (byte) 0xFF;
        assertThat(key.bytes()[0]).isNotEqualTo((byte) 0xFF);
    }
}
