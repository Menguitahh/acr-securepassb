package com.securepass.modules.invitation.application;

import java.util.UUID;

/**
 * US-42: el residente decidio sobre una estadia en curso — extendio el
 * pase (WBS-71) o pidio que la visita se retire. Se publica AFTER_COMMIT
 * desde los use cases de invitation; el listener de `notification/`
 * dispara push a los guardias con turno abierto en la garita del ingreso
 * (WBS-72).
 *
 * Payload minimo: los datos para armar el push (residente, invitado,
 * horario) los resuelve el listener via query dedicada; el evento solo
 * discrimina que estadia y que tipo de decision.
 */
public record ResidentDecisionMadeEvent(
        UUID neighborhoodId,
        UUID invitationId,
        Kind kind
) {
    public ResidentDecisionMadeEvent {
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        if (invitationId == null) throw new IllegalArgumentException("invitationId requerido");
        if (kind == null) throw new IllegalArgumentException("kind requerido");
    }

    public enum Kind { EXTENDED, EXIT_REQUESTED }
}
