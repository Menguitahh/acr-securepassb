package com.securepass.modules.neighborhood.application;

import com.securepass.modules.access.application.OpenShiftQuery;
import com.securepass.modules.neighborhood.domain.Lot;
import com.securepass.shared.exception.StateConflictException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Autocomplete de lotes para la garita: soporta el flujo de POST
 * `/access/manual`, que exige `lotId` UUID pero el guardia solo conoce el
 * codigo del lote.
 *
 * Restricciones:
 *  - Aislamiento por barrio: siempre filtra por `neighborhoodId` del actor.
 *  - Requiere turno abierto (BR-09), mismo criterio que `ManualAccessUseCase`.
 *  - Solo lotes `ACTIVE`.
 */
@Service
public class SearchLotsForGuardUseCase {

    private static final int LIMIT = 10;
    private static final int MIN_QUERY_LENGTH = 1;

    private final LotRepository lots;
    private final OpenShiftQuery shifts;

    public SearchLotsForGuardUseCase(LotRepository lots, OpenShiftQuery shifts) {
        this.lots = lots;
        this.shifts = shifts;
    }

    // WBS-36: el ingreso manual funciona sin red. La garita guarda los lotes
    // activos del barrio para elegir el destino offline.
    // ponytail: tope fijo; paginar si un barrio supera los 2000 lotes.
    static final int ALL_LIMIT = 2000;

    public List<Lot> listActive(UUID neighborhoodId, UUID guardId) {
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        if (!shifts.hasOpenShift(guardId)) {
            throw new StateConflictException("el guardia no tiene turno abierto");
        }
        return lots.searchActiveByCodePrefix(neighborhoodId, "", ALL_LIMIT);
    }

    public List<Lot> search(UUID neighborhoodId, UUID guardId, String q) {
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        if (guardId == null) throw new IllegalArgumentException("guardId requerido");
        if (q == null) throw new IllegalArgumentException("q requerido");
        String normalized = q.trim();
        if (normalized.length() < MIN_QUERY_LENGTH) {
            throw new IllegalArgumentException("q debe tener al menos " + MIN_QUERY_LENGTH + " caracter");
        }
        if (!shifts.hasOpenShift(guardId)) {
            throw new StateConflictException("el guardia no tiene turno abierto");
        }
        return lots.searchActiveByCodePrefix(neighborhoodId, normalized, LIMIT);
    }
}
