package com.securepass.modules.access.application;

import com.securepass.shared.security.CurrentUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * WBS-117 / WBS-118: sync de permisos WORKER activos del barrio para el
 * scanner offline. El mobile cachea el resultado en SQLite y evalua franja
 * localmente (WBS-118); esto es la fuente autoritativa.
 *
 * <p>Cursor por {@code updated_at ASC} + LIMIT — el mobile pasa el mayor
 * updated_at recibido como {@code since} en la proxima llamada. Rangos de
 * fecha in-clusive del lado derecho (>=) para no perder filas con timestamps
 * repetidos: preferimos duplicar un par de filas en el proximo batch antes
 * que dropearlas del cache.
 *
 * <p>ponytail: MAX_LIMIT=1000. Un barrio con >1000 workers activos + updates
 * en la misma ventana requiere paginar. Fase 13 nunca llega.
 */
@Service
public class ListActiveWorkerPermissionsUseCase {

    private static final int MAX_LIMIT = 1000;

    private final ActiveWorkerPermissionsQuery query;

    public ListActiveWorkerPermissionsUseCase(ActiveWorkerPermissionsQuery query) {
        this.query = query;
    }

    /**
     * #180: {@code enrolledUserIds} sale completo aunque venga {@code since}:
     * el scanner reemplaza ese cache entero en cada sync.
     */
    public Snapshot list(CurrentUser actor, Instant since) {
        if (actor == null) throw new IllegalArgumentException("actor requerido");
        if (actor.neighborhoodId() == null) throw new AccessDeniedException("usuario sin barrio");
        return new Snapshot(
                query.listActive(actor.neighborhoodId(), since, MAX_LIMIT),
                query.enrolledWorkerIds(actor.neighborhoodId()),
                query.enrolledInvitationIds(actor.neighborhoodId()));
    }

    public record Snapshot(List<ActiveWorkerPermissionRow> permissions, List<UUID> enrolledUserIds,
                           List<UUID> enrolledInvitationIds) {}
}
