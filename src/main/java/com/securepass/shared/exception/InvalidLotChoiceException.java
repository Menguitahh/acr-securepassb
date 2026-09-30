package com.securepass.shared.exception;

/**
 * #217: el lotId que manda la garita no es uno que el backend habilite para
 * ese token (lote ajeno, o distinto del de la invitacion), o falta cuando hay
 * que elegir (#219). Error del cliente: 400 en validate y enroll, REJECTED
 * por evento en sync.
 */
public class InvalidLotChoiceException extends RuntimeException {
    public InvalidLotChoiceException() {
        super("el lote elegido no esta habilitado para este pase");
    }

    public InvalidLotChoiceException(String message) {
        super(message);
    }
}
