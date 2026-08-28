package com.securepass.shared.exception;

/**
 * WBS-110 / US-57: alta de familiar mayor de 16 sin email. La cuenta se crea
 * en {@code PENDING_ACTIVATION} y el link viaja por email; sin email no hay
 * activacion posible. 400 con code {@code EMAIL_REQUIRED}.
 */
public class EmailRequiredException extends RuntimeException {

    public EmailRequiredException() {
        super("email requerido para dar de alta un familiar mayor de edad");
    }
}
