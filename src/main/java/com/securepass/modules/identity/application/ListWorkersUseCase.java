package com.securepass.modules.identity.application;

import com.securepass.shared.security.CurrentUser;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * WBS-115 / US-63: lista los trabajadores activos del lote. Solo activos:
 * baja no aparece en el listado. Gate en el controller.
 */
@AllArgsConstructor
@Service
public class ListWorkersUseCase {

    private final WorkerPermissionRepository workers;

    public List<WorkerListingRow> list(CurrentUser actor, UUID lotId) {
        if (actor == null) throw new IllegalArgumentException("actor requerido");
        if (lotId == null) throw new IllegalArgumentException("lotId requerido");
        return workers.listActiveByLot(lotId);
    }
}
