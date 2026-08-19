package com.securepass.shared.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;

public final class Ed25519Signer {

    /**
     * PKCS8 SubjectPrivateKeyInfo prefix para Ed25519: 16 bytes + 32 bytes de seed = 48.
     * Ver RFC 8410.
     */
    private static final byte[] PKCS8_PREFIX = new byte[]{
            0x30, 0x2E, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06,
            0x03, 0x2B, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20
    };

    private Ed25519Signer() {}

    public static byte[] sign(byte[] seed, byte[] message) {
        if (seed == null || seed.length != 32) throw new IllegalArgumentException("seed debe ser 32 bytes");
        if (message == null) throw new IllegalArgumentException("message requerido");
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKeyFromSeed(seed));
            signer.update(message);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 sign fallo", e);
        }
    }

    private static PrivateKey privateKeyFromSeed(byte[] seed) throws GeneralSecurityException {
        byte[] encoded = new byte[PKCS8_PREFIX.length + seed.length];
        System.arraycopy(PKCS8_PREFIX, 0, encoded, 0, PKCS8_PREFIX.length);
        System.arraycopy(seed, 0, encoded, PKCS8_PREFIX.length, seed.length);
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }
}
