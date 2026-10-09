package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.ListActiveWorkerPermissionsUseCase;

import java.util.List;
import java.util.UUID;

/**
 * #180: {@code enrolledUserIds} son los trabajadores de {@code permissions}
 * que ya presentaron el DNI en el barrio. Sin red, un trabajador en franja que
 * no esta en la lista no entra: el guardia lo registra manualmente (US-66).
 * securepassf#179: {@code enrolledInvitationIds} son las invitaciones activas
 * cuyo invitado ya presento el DNI en el barrio. Salen completas, como
 * {@code enrolledUserIds}: el scanner reemplaza su cache entero.
 */
public record PermissionsSyncResponse(List<WorkerPermissionSyncResponse> permissions, List<UUID> enrolledUserIds,
                                      List<UUID> enrolledInvitationIds) {

    public static PermissionsSyncResponse from(ListActiveWorkerPermissionsUseCase.Snapshot s) {
        return new PermissionsSyncResponse(
                s.permissions().stream().map(WorkerPermissionSyncResponse::from).toList(),
                s.enrolledUserIds(),
                s.enrolledInvitationIds());
    }
}
