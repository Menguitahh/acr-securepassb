package com.securepass.shared.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

public final class Ed25519Verifier {

    /**
     * X.509 SubjectPublicKeyInfo prefix para Ed25519: 12 bytes + 32 bytes raw = 44.
     * Ver RFC 8410.
     */
    private static final byte[] SPKI_PREFIX = new byte[]{
            0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65,
            0x70, 0x03, 0x21, 0x00
    };

    private Ed25519Verifier() {}

    public static boolean verify(byte[] publicRaw, byte[] message, byte[] signature) {
        if (publicRaw == null || publicRaw.length != 32) return false;
        if (message == null || signature == null) return false;
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKeyFromRaw(publicRaw));
            verifier.update(message);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private static PublicKey publicKeyFromRaw(byte[] raw) throws GeneralSecurityException {
        byte[] encoded = new byte[SPKI_PREFIX.length + raw.length];
        System.arraycopy(SPKI_PREFIX, 0, encoded, 0, SPKI_PREFIX.length);
        System.arraycopy(raw, 0, encoded, SPKI_PREFIX.length, raw.length);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
    }
}
