package com.securepass.shared.exception;

import java.util.UUID;

/**
 * Configuracion del barrio incompleta para atender la operacion pedida
 * (por ejemplo, no hay signing key activa). Condicion de aprovisionamiento
 * previsible, no error inesperado: el cliente no puede corregirla, el admin
 * del barrio si.
 *
 * Mapea a 503 con code {@code NEIGHBORHOOD_NOT_PROVISIONED} en el
 * {@link GlobalExceptionHandler}.
 */
public class NeighborhoodNotProvisionedException extends RuntimeException {

    private final UUID neighborhoodId;
    private final String resource;

    public NeighborhoodNotProvisionedException(UUID neighborhoodId, String resource) {
        super("Barrio " + neighborhoodId + " sin " + resource);
        this.neighborhoodId = neighborhoodId;
        this.resource = resource;
    }

    public UUID neighborhoodId() { return neighborhoodId; }
    public String resource() { return resource; }
}
