package com.securepass.shared.exception;

import java.util.UUID;

/**
 * WBS-111 / US-58: el titular quiso registrar en el padron a alguien con
 * >= edad maxima. Mapea a 400 con code {@code AGE_OVER_16} y
 * {@code redirectTo} apuntando al alta de familiar (WBS-110) — es el flujo
 * espejo de {@link AgeUnderMinimumException}.
 */
public class AgeOverMaximumException extends RuntimeException {

    private final int maxAge;
    private final UUID lotId;

    public AgeOverMaximumException(int maxAge, UUID lotId) {
        super("edad >= " + maxAge + " para lote " + lotId);
        this.maxAge = maxAge;
        this.lotId = lotId;
    }

    public int maxAge() { return maxAge; }
    public UUID lotId() { return lotId; }
}
