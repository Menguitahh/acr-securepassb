package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.Minor;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.security.CurrentUser;
import lombok.AllArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * WBS-111: baja del padron. Hard delete — los menores no dejan historial
 * (no generan permisos ni accesos), soft delete seria peso sin uso.
 */
@AllArgsConstructor
@Service
public class DeleteMinorUseCase {

    private final MinorRepository minors;

    @Transactional
    public void delete(CurrentUser actor, UUID lotId, UUID minorId) {
        if (actor == null) throw new IllegalArgumentException("actor requerido");
        if (lotId == null) throw new IllegalArgumentException("lotId requerido");
        if (minorId == null) throw new IllegalArgumentException("minorId requerido");

        Minor minor = minors.findById(minorId)
                .orElseThrow(() -> new ResourceNotFoundException("minor", minorId));

        // Aislamiento cross-barrio: 403 alineado con ListFamilyMembersUseCase (#135).
        if (!minor.neighborhoodId().equals(actor.neighborhoodId())) {
            throw new AccessDeniedException("recurso de otro barrio");
        }
        // Cross-lote dentro del mismo barrio: 404. Aca no hay riesgo de
        // enumeracion inter-barrio; es un id mal ruteado del cliente.
        if (!minor.lotId().equals(lotId)) {
            throw new ResourceNotFoundException("minor", minorId);
        }

        minors.delete(minorId);
    }
}
