package com.securepass.shared.security;

import com.securepass.shared.schedule.ScheduleWindow;

import java.util.UUID;

/**
 * Permiso de trabajador sobre un lote: quien es, en que lote trabaja, con
 * que etiqueta de servicio y en que franja. Se persiste como fila en
 * {@code lot_permissions} con {@code permission_type='WORKER'} y los
 * atributos en el JSONB (WBS-114, spec §L, US-62).
 *
 * <p>{@code service} es texto libre acotado, no enum: la etiqueta la lee el
 * guardia, el sistema no ramifica por ella. Un enum dejaria fuera casos
 * reales (profesor de piano, kinesiologo, paseador) y los colapsaria en
 * "OTRO", perdiendo el dato util.
 */
public record WorkerPermission(
        UUID userId,
        UUID lotId,
        String service,
        ScheduleWindow schedule,
        boolean active
) {
    public static final int SERVICE_MAX = 80;

    public WorkerPermission {
        if (userId == null) throw new IllegalArgumentException("userId requerido");
        if (lotId == null) throw new IllegalArgumentException("lotId requerido");
        if (service == null || service.isBlank()) {
            throw new IllegalArgumentException("service requerido");
        }
        if (service.length() > SERVICE_MAX) {
            throw new IllegalArgumentException("service excede " + SERVICE_MAX);
        }
        if (schedule == null) throw new IllegalArgumentException("schedule requerido");
    }
}
