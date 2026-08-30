package com.securepass.modules.invitation.application;

import java.util.Optional;
import java.util.UUID;

/**
 * WBS-116: puerto para consultar el estado de permisos WORKER desde el
 * modulo invitation (que no depende de identity). El QR se emite solo si
 * el user tiene al menos un permiso WORKER activo; sirve para localizar
 * el barrio del trabajador cuando su JWT no lo lleva (workers no tienen
 * membership).
 */
public interface WorkerActivePermissionQuery {

    /**
     * Retorna el neighborhood_id de cualquier permiso WORKER activo del
     * usuario. Empty si no tiene ninguno. En Fase 13 asumimos un solo
     * barrio; si aparecen varios, devuelve el primero (deterministico por
     * ORDER BY).
     */
    Optional<UUID> findNeighborhoodOfActiveWorker(UUID userId);
}
