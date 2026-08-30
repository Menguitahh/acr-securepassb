package com.securepass.modules.invitation.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WBS-116: puerto de persistencia de {@code worker_tokens}. Un token activo
 * por (user, barrio) — ver ponytail: en la migracion V15.
 */
public interface WorkerTokenRepository {

    /** Retorna el token activo (revoked_at IS NULL) del user, si existe. */
    Optional<WorkerTokenRow> findActive(UUID userId);

    /** Inserta un nuevo token activo. Falla si ya hay uno activo para ese user. */
    void insertActive(UUID userId, UUID neighborhoodId, UUID jti);

    /**
     * Revoca el token activo del user (si existe) seteando revoked_at=now().
     * Retorna cuantas filas actualizo (0 o 1).
     */
    int revokeActive(UUID userId);

    /** Lista JTIs revocados en el barrio, opcionalmente desde una fecha. */
    List<RevokedEntry> listRevoked(UUID neighborhoodId, Instant since, int limit);

    record WorkerTokenRow(UUID userId, UUID neighborhoodId, UUID jti, Instant issuedAt) {}
}
