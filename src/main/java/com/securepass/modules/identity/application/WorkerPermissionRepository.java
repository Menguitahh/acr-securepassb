package com.securepass.modules.identity.application;

import com.securepass.shared.schedule.ScheduleWindow;
import com.securepass.shared.security.WorkerPermission;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Puerto de persistencia de permisos WORKER sobre {@code lot_permissions}.
 * Es un puerto separado de {@link FamilyMemberRepository} porque el JSONB de
 * WORKER tiene otro shape (service/weekdays/dailyFrom/dailyUntil) y no
 * comparte {@code can_invite/relation}.
 *
 * Estado: {@link #find} devuelve la fila incluso si esta inactiva — es lo
 * que necesita el flujo de dedupe/reactivacion. {@link #listActiveByLot} solo
 * activas — es el listado que ve el titular.
 */
public interface WorkerPermissionRepository {

    Optional<WorkerPermission> find(UUID userId, UUID lotId);

    List<WorkerListingRow> listActiveByLot(UUID lotId);

    void save(UUID userId, UUID lotId, String service, ScheduleWindow schedule);

    void reactivate(UUID userId, UUID lotId, String service, ScheduleWindow schedule);

    /** Retorna cuantas filas actualizo (0 si no existia la fila WORKER en ese lote). */
    int updateAttributes(UUID userId, UUID lotId, String service, ScheduleWindow schedule);

    /** Baja: {@code active=false}. Retorna cuantas filas actualizo. Idempotente. */
    int deactivate(UUID userId, UUID lotId);

    /**
     * Cuenta permisos WORKER activos del usuario en cualquier lote/barrio.
     * Usado por la rotacion del JTI (WBS-116): si post-baja queda en 0, se
     * revoca el {@code worker_token} y el JTI viejo entra en /access/revoked.
     */
    int countActiveByUser(UUID userId);
}
