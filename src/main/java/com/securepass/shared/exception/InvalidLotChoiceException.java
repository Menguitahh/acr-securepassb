package com.securepass.shared.exception;

/**
 * #217: el lotId que manda la garita no es uno que el backend habilite para
 * ese token (lote fuera de franja, ajeno, o distinto del de la invitacion).
 * Error del cliente: 400 en validate, REJECTED por evento en sync.
 */
public class InvalidLotChoiceException extends RuntimeException {
    public InvalidLotChoiceException() {
        super("el lote elegido no esta habilitado para este pase");
    }
}
