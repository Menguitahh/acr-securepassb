package com.securepass.modules.invitation.application;

import com.securepass.modules.invitation.domain.SigningKey;
import com.securepass.shared.crypto.AesGcmCipher;
import com.securepass.shared.crypto.Ed25519KeyFactory;
import com.securepass.shared.crypto.Ed25519KeyPair;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class SigningKeyIssuer {

    private static final int KID_BYTES = 4;

    private final SigningKeyRepository repo;
    private final AesGcmCipher cipher;
    private final SecureRandom rng = new SecureRandom();

    public SigningKeyIssuer(SigningKeyRepository repo, AesGcmCipher cipher) {
        this.repo = repo;
        this.cipher = cipher;
    }

    @Transactional
    public IssuedKey issueForNeighborhood(UUID neighborhoodId) {
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        Instant now = Instant.now();
        repo.findActive(neighborhoodId).ifPresent(existing -> repo.markInactive(existing.id(), now));
        Ed25519KeyPair pair = Ed25519KeyFactory.generate();
        String kid = generateKid();
        String encryptedPrivate = cipher.encrypt(pair.privateKey());
        SigningKey signingKey = new SigningKey(
                UUID.randomUUID(), neighborhoodId, kid,
                pair.publicKey(), encryptedPrivate, true, now, null);
        repo.save(signingKey);
        return new IssuedKey(kid, pair.publicKey());
    }

    private String generateKid() {
        byte[] bytes = new byte[KID_BYTES];
        rng.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public record IssuedKey(String kid, byte[] publicKey) {}
}
