package com.securepass.shared.crypto;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.EdECPrivateKey;
import java.util.Arrays;

public final class Ed25519KeyFactory {

    private static final int RAW_KEY_LENGTH = 32;

    private Ed25519KeyFactory() {}

    public static Ed25519KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            KeyPair pair = generator.generateKeyPair();
            byte[] encodedPublic = pair.getPublic().getEncoded();
            byte[] rawPublic = Arrays.copyOfRange(
                    encodedPublic, encodedPublic.length - RAW_KEY_LENGTH, encodedPublic.length);
            byte[] rawPrivate = ((EdECPrivateKey) pair.getPrivate()).getBytes()
                    .orElseThrow(() -> new IllegalStateException("Ed25519 seed no accesible"));
            return new Ed25519KeyPair(rawPublic, rawPrivate);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 no disponible en esta JVM", e);
        }
    }
}
