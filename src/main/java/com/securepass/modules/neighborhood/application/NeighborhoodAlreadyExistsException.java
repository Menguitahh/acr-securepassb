package com.securepass.modules.neighborhood.application;

/**
 * Provisioning intento sobre un barrio que ya existe (por name o slug).
 * El comando falla antes de escribir nada.
 */
public class NeighborhoodAlreadyExistsException extends RuntimeException {

    private final String by;
    private final String value;

    public NeighborhoodAlreadyExistsException(String by, String value) {
        super("Barrio ya existe: " + by + "=" + value);
        this.by = by;
        this.value = value;
    }

    public String by() { return by; }
    public String value() { return value; }
}
