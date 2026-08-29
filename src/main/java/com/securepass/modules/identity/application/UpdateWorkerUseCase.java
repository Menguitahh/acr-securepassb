package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.User;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.exception.StateConflictException;
import com.securepass.shared.schedule.ScheduleWindow;
import com.securepass.shared.security.CurrentUser;
import com.securepass.shared.security.WorkerPermission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * WBS-115 / US-63, US-64: edicion y baja de un trabajador en un lote.
 *
 * PATCH parcial: {@code service} y/o {@code schedule} y/o {@code active}.
 * La baja es {@code active=false} idempotente. Sin cascada de invitaciones
 * (workers no crean invitaciones); la rotacion del JTI del QR permanente
 * queda para WBS-116 (PR-3).
 *
 * Aislamiento cross-lote (US-64): el UPDATE filtra por {@code (user_id, lot_id)}
 * — un mismo user en otros lotes conserva sus permisos WORKER activos.
 */
@Service
public class UpdateWorkerUseCase {

    private final WorkerPermissionRepository workers;
    private final UserRepository users;

    public UpdateWorkerUseCase(WorkerPermissionRepository workers, UserRepository users) {
        this.workers = workers;
        this.users = users;
    }

    @Transactional
    public Result update(CurrentUser actor, UUID lotId, UUID workerUserId,
                         String service, ScheduleWindow schedule, Boolean active) {
        if (actor == null) throw new IllegalArgumentException("actor requerido");
        if (lotId == null) throw new IllegalArgumentException("lotId requerido");
        if (workerUserId == null) throw new IllegalArgumentException("workerUserId requerido");
        boolean editing = service != null || schedule != null;
        if (!editing && active == null) {
            throw new IllegalArgumentException("service/schedule o active requerido");
        }
        if ((service == null) != (schedule == null)) {
            // El schedule y el service se editan juntos: recrear attributes con
            // uno solo dejaria el otro como null en el JSONB. El DTO ya lo
            // garantiza pero validamos aca por si alguien llama al use case
            // directamente (tests).
            throw new IllegalArgumentException("service y schedule van juntos");
        }

        WorkerPermission current = workers.find(workerUserId, lotId)
                .orElseThrow(() -> new ResourceNotFoundException("trabajador", workerUserId));

        Outcome outcome;
        if (Boolean.FALSE.equals(active)) {
            if (!current.active()) {
                outcome = Outcome.ALREADY_INACTIVE;
            } else {
                workers.deactivate(workerUserId, lotId);
                outcome = Outcome.DEACTIVATED;
            }
        } else if (editing) {
            workers.updateAttributes(workerUserId, lotId, service, schedule);
            outcome = Outcome.UPDATED;
        } else if (current.active()) {
            // active=TRUE sobre una fila ya activa: idempotente.
            outcome = Outcome.ALREADY_ACTIVE;
        } else {
            // Reactivar exige re-declarar service y schedule: la fila esta
            // inactiva y los atributos de la baja podrian estar rancios; el
            // titular usa POST /workers con el body completo. 409 al cliente
            // porque el estado del recurso impide la operacion.
            throw new StateConflictException(
                    "reactivar requiere reenviar service y schedule via POST /workers");
        }

        WorkerPermission after = workers.find(workerUserId, lotId).orElseThrow();
        User user = users.findById(workerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("usuario", workerUserId));

        return new Result(
                workerUserId, user.fullName(), user.email(), user.documentNumber(),
                after.service(), after.schedule(), after.active(), outcome);
    }

    public enum Outcome {
        UPDATED,
        DEACTIVATED,
        ALREADY_INACTIVE,
        ALREADY_ACTIVE
    }

    public record Result(
            UUID userId, String fullName, String email, String documentNumber,
            String service, ScheduleWindow schedule, boolean active,
            Outcome outcome) {}
}
