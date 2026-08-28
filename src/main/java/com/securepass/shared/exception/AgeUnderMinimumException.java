package com.securepass.shared.exception;

import java.util.UUID;

/**
 * WBS-110 / US-57: el titular quiso agregar un familiar menor de la edad
 * minima para tener cuenta propia. No es un error del sistema — es el flujo
 * incorrecto: los menores van al padron del lote (WBS-111 / US-58) y no
 * reciben credenciales. Mapea a 400 con code {@code AGE_UNDER_16} y
 * {@code redirectTo} apuntando al endpoint del padron.
 */
public class AgeUnderMinimumException extends RuntimeException {

    private final int minAge;
    private final UUID lotId;

    public AgeUnderMinimumException(int minAge, UUID lotId) {
        super("edad menor a " + minAge + " para lote " + lotId);
        this.minAge = minAge;
        this.lotId = lotId;
    }

    public int minAge() { return minAge; }
    public UUID lotId() { return lotId; }
}
