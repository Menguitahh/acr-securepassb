package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.AccessDecision;
import com.securepass.modules.invitation.application.WorkerValidationQuery;
import com.securepass.shared.exception.InvalidLotChoiceException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * #219: lotes que habilitan a un trabajador en {@code capturedAt}, con la
 * eleccion de la garita. Lo usan validate y enroll para decidir igual.
 */
final class WorkerLots {

    private WorkerLots() {}

    /**
     * Lotes en franja en {@code capturedAt}; con {@code lotId}, solo ese.
     * Vacio es OUT_OF_SCHEDULE, una decision de acceso: tambien cuando el lote
     * elegido es del trabajador pero su franja cerro (entre la lista y el
     * envio). Un {@code lotId} que no es de ningun permiso del trabajador en
     * el barrio es error del cliente: {@link InvalidLotChoiceException}.
     */
    static List<AccessDecision.LotOption> inSlot(WorkerValidationQuery.WorkerContext ctx, Instant capturedAt,
                                                 UUID lotId) {
        if (lotId != null && ctx.lots().stream().noneMatch(l -> l.lotId().equals(lotId))) {
            throw new InvalidLotChoiceException();
        }
        return ctx.lots().stream()
                .filter(l -> lotId == null || l.lotId().equals(lotId))
                .filter(l -> l.schedule().contains(capturedAt, ctx.neighborhoodTimezone()))
                .map(l -> new AccessDecision.LotOption(l.lotId(), l.lotCode(), l.service()))
                .toList();
    }
}
