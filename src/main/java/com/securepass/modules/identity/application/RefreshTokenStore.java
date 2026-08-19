package com.securepass.modules.identity.application;

import java.util.UUID;

public interface RefreshTokenStore {

    /** Persiste un refresh recien emitido para la familia dada. Refresca el TTL de la familia. */
    void save(String rawToken, UUID familyId, UUID userId);

    /** Marca un refresh como usado. Detecta reuso (replay) y familias revocadas. */
    ConsumeResult consume(String rawToken);

    /** Invalida todos los refresh actuales y futuros de una familia. */
    void revokeFamily(UUID familyId);

    sealed interface ConsumeResult permits Consumed, ReplayDetected, Invalid {}

    record Consumed(UUID familyId, UUID userId) implements ConsumeResult {}

    record ReplayDetected(UUID familyId) implements ConsumeResult {}

    record Invalid() implements ConsumeResult {}
}
