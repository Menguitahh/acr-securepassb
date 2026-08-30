package com.securepass.modules.invitation.application;

import com.securepass.modules.invitation.domain.SigningKey;
import com.securepass.shared.crypto.AesGcmCipher;
import com.securepass.shared.crypto.Ed25519Signer;
import com.securepass.shared.exception.NeighborhoodNotProvisionedException;
import com.securepass.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * WBS-116 / US-65, US-66: emite (o rehidrata) el QR permanente del trabajador.
 *
 * <p>Get-or-create sobre {@code worker_tokens}: si el user ya tiene un JTI
 * activo, se re-firma con la clave del barrio y se devuelve. Si no, se
 * genera y persiste uno nuevo. Ed25519 es deterministico para el mismo
 * seed+message, asi que dos llamadas con el mismo kid dan el mismo SP1
 * byte-a-byte; no acumula tokens fantasma.
 *
 * <p>Guard: el user tiene que tener al menos un permiso WORKER activo
 * (WBS-115). Si no, {@link ResourceNotFoundException} y el cliente muestra
 * "sin permisos vigentes". Sin permiso no hay QR — el token queda revocado
 * automaticamente por {@link #issueForCurrentUser} nunca crear uno sin base.
 */
@Service
public class IssueWorkerTokenUseCase {

    static final int MAX_PAYLOAD_BYTES = 300;
    private static final int PAYLOAD_VERSION = 1;

    private final WorkerActivePermissionQuery permissions;
    private final WorkerTokenRepository tokens;
    private final SigningKeyRepository keys;
    private final AesGcmCipher cipher;
    private final QrTokenCodec codec;

    public IssueWorkerTokenUseCase(
            WorkerActivePermissionQuery permissions,
            WorkerTokenRepository tokens,
            SigningKeyRepository keys,
            AesGcmCipher cipher,
            QrTokenCodec codec) {
        this.permissions = permissions;
        this.tokens = tokens;
        this.keys = keys;
        this.cipher = cipher;
        this.codec = codec;
    }

    @Transactional
    public IssuedWorkerToken issueForCurrentUser(UUID userId) {
        if (userId == null) throw new IllegalArgumentException("userId requerido");

        UUID neighborhoodId = permissions.findNeighborhoodOfActiveWorker(userId)
                .orElseThrow(() -> new ResourceNotFoundException("worker permissions", userId));

        SigningKey key = keys.findActive(neighborhoodId)
                .orElseThrow(() -> new NeighborhoodNotProvisionedException(
                        neighborhoodId, "signing key activa"));

        UUID jti;
        Optional<WorkerTokenRepository.WorkerTokenRow> existing = tokens.findActive(userId);
        if (existing.isPresent()) {
            jti = existing.get().jti();
        } else {
            jti = UUID.randomUUID();
            tokens.insertActive(userId, neighborhoodId, jti);
        }

        QrTokenPayload payload = QrTokenPayload.worker(PAYLOAD_VERSION, key.kid(), jti, neighborhoodId, userId);
        byte[] payloadBytes = codec.serialize(payload);
        if (payloadBytes.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalStateException(
                    "payload excede " + MAX_PAYLOAD_BYTES + " bytes: " + payloadBytes.length);
        }
        byte[] seed = cipher.decrypt(key.privateKeyEnc());
        byte[] signature = Ed25519Signer.sign(seed, payloadBytes);
        return new IssuedWorkerToken(jti, codec.encode(payload, signature));
    }

    public record IssuedWorkerToken(UUID jti, String sp1) {}
}
