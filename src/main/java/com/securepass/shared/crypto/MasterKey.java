package com.securepass.shared.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;

@Component
public class MasterKey {

    private static final int REQUIRED_BYTES = 32;

    private final byte[] bytes;

    public MasterKey(@Value("${securepass.master-key}") String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException(
                    "SECUREPASS_MASTER_KEY es obligatoria; configurar la variable de entorno con una clave de "
                            + REQUIRED_BYTES + " bytes codificada en base64.");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "SECUREPASS_MASTER_KEY no es base64 valido; generar con `openssl rand -base64 32`.", e);
        }
        if (decoded.length != REQUIRED_BYTES) {
            throw new IllegalStateException(
                    "SECUREPASS_MASTER_KEY debe decodificar a " + REQUIRED_BYTES + " bytes; recibidos " + decoded.length);
        }
        this.bytes = decoded;
    }

    public byte[] bytes() {
        return bytes.clone();
    }
}
