package com.securepass.shared.exception;

/**
 * Un token de activacion o recuperacion no existe, esta vencido o fue
 * consumido. Deliberadamente no distingue los tres casos: la respuesta
 * HTTP debe ser identica para no revelar si un token dado alguna vez
 * fue valido.
 */
public class TokenGoneException extends RuntimeException {

    public static final TokenGoneException INSTANCE = new TokenGoneException();

    private TokenGoneException() {
        super("token invalido o vencido");
    }
}
