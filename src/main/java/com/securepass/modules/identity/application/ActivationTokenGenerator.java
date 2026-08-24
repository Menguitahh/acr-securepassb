package com.securepass.modules.identity.application;

import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Genera tokens de 256 bits con {@link SecureRandom} y los codifica en
 * base64url sin padding: 43 caracteres URL-safe, aptos para viajar en
 * el path de un enlace. El caller emite el valor en claro (email) y
 * persiste unicamente el SHA-256.
 */
@Component
public class ActivationTokenGenerator {

    public static final int TOKEN_BYTES = 32;
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final SecureRandom random = new SecureRandom();

    public Generated generate() {
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String value = URL_ENCODER.encodeToString(raw);
        return new Generated(value, hash(value));
    }

    public static byte[] hash(String tokenValue) {
        if (tokenValue == null || tokenValue.isBlank()) {
            throw new IllegalArgumentException("token requerido");
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(tokenValue.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    public record Generated(String plaintext, byte[] hash) {
        public Generated {
            if (plaintext == null || plaintext.isBlank()) throw new IllegalArgumentException("plaintext requerido");
            if (hash == null || hash.length != 32) throw new IllegalArgumentException("hash SHA-256 requerido");
            hash = hash.clone();
        }

        @Override
        public byte[] hash() {
            return hash.clone();
        }
    }
}
