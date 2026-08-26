package com.securepass.shared.crypto;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class AesGcmCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom rng = new SecureRandom();

    public AesGcmCipher(MasterKey masterKey) {
        this.key = new SecretKeySpec(masterKey.bytes(), "AES");
    }

    public String encrypt(byte[] plaintext) {
        if (plaintext == null) throw new IllegalArgumentException("plaintext requerido");
        try {
            byte[] iv = new byte[IV_LENGTH];
            rng.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext);
            byte[] combined = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ct, 0, combined, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM cifrado fallo", e);
        }
    }

    public byte[] decrypt(String encoded) {
        if (encoded == null || encoded.isBlank()) throw new IllegalArgumentException("ciphertext requerido");
        byte[] combined;
        try {
            combined = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ciphertext no es base64 valido", e);
        }
        if (combined.length <= IV_LENGTH) throw new IllegalStateException("ciphertext demasiado corto");
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
        byte[] ct = new byte[combined.length - IV_LENGTH];
        System.arraycopy(combined, IV_LENGTH, ct, 0, ct.length);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return cipher.doFinal(ct);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM descifrado fallo (posible tampereo)", e);
        }
    }
}
