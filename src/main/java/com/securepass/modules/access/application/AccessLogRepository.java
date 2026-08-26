package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.AccessLog;

import java.util.Optional;
import java.util.UUID;

public interface AccessLogRepository {

    /**
     * Guarda el log. Si ya existe uno con el mismo clientEventId (retry en
     * red intermitente), devuelve el existente sin duplicar. La unicidad se
     * apoya en la unique constraint de access_logs.client_event_id.
     * Comparar el `id` devuelto con el original permite distinguir
     * insercion fresca de conflicto ganado por otro proceso.
     */
    AccessLog saveIdempotent(AccessLog log);

    Optional<AccessLog> findByClientEventId(UUID clientEventId);
}
