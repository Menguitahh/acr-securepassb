package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.ActiveWorkerPermissionRow;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Fila del sync de permisos WORKER. Cada fila es autonoma: trae todo lo que el
 * scanner necesita para evaluar validate offline sin joins locales.
 *
 * <p><b>Invariante de la respuesta</b>: todas las filas de una misma respuesta
 * comparten {@code timezone} (es propiedad del barrio, no del permiso; el sync
 * es siempre de un solo barrio, filtrado por el barrio del actor). La tz viaja
 * repetida a proposito para preservar el shape plano: evita un envelope
 * {@code {timezone, permissions[]}} que complicaria el consumo por ~30 bytes
 * por fila de redundancia. Ver WBS-117 issue #149 para la razon (la franja se
 * evalua en la zona del barrio, no del dispositivo ni del login).
 *
 * <p><b>Contrato para el cliente (WBS-118)</b>: el scanner debe hacer
 * <b>full-replace</b> del cache local por barrio en cada sync exitoso, nunca
 * merge por fila. Un merge por fila permitiria coexistir filas con timezone
 * distinto si el barrio cambio de zona entre syncs, sin regla de cual gana.
 * El cambio de zona de un barrio es raro (reformas de husos horarios), pero
 * la regla es barata y elimina la ambiguedad.
 */
public record WorkerPermissionSyncResponse(
        UUID userId,
        UUID jti,
        UUID lotId,
        String lotCode,
        String service,
        List<Integer> weekdays,
        String dailyFrom,
        String dailyUntil,
        String timezone,
        Instant updatedAt
) {
    public static WorkerPermissionSyncResponse from(ActiveWorkerPermissionRow row) {
        return new WorkerPermissionSyncResponse(
                row.userId(), row.jti(), row.lotId(), row.lotCode(), row.service(),
                row.schedule().weekdays(),
                row.schedule().dailyFrom().toString(),
                row.schedule().dailyUntil().toString(),
                row.timezone(),
                row.updatedAt());
    }
}
