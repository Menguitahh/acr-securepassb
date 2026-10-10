package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.Minor;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.security.CurrentUser;
import lombok.AllArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * WBS-111 / US-58: listado del padron de menores del lote.
 *
 * Aislamiento cross-barrio: el gate deja pasar {@code hasRole('ADMIN')} sin
 * evaluar {@code lot_permissions}, por eso este use case chequea que el
 * lote pertenezca al barrio del actor. Mismo criterio que
 * {@link ListFamilyMembersUseCase} (#135): 403 con
 * {@code "recurso de otro barrio"} — no cambiar a 404 sin cambiar tambien
 * /members, si no dos endpoints devuelven cosas distintas para el mismo caso.
 */
@AllArgsConstructor
@Service
public class ListMinorsUseCase {

    private final MinorRepository minors;

    public List<Minor> list(CurrentUser actor, UUID lotId) {
        if (actor == null) throw new IllegalArgumentException("actor requerido");
        if (lotId == null) throw new IllegalArgumentException("lotId requerido");

        UUID lotBarrio = minors.findLotNeighborhood(lotId)
                .orElseThrow(() -> new ResourceNotFoundException("lote", lotId));
        if (!lotBarrio.equals(actor.neighborhoodId())) {
            throw new AccessDeniedException("recurso de otro barrio");
        }

        return minors.findByLot(lotId);
    }
}
