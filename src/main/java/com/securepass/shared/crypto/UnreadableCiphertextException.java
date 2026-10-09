package com.securepass.shared.crypto;

/**
 * #157 E: el server no puede descifrar un secreto propio (master key rotada,
 * fila corrupta o tampereada). No es error del cliente ni un bug opaco: el
 * handler lo devuelve como 503 con codigo legible. Extiende
 * IllegalStateException para no cambiar a quien ya lo atrapaba asi.
 */
public class UnreadableCiphertextException extends IllegalStateException {

    public UnreadableCiphertextException(String message) {
        super(message);
    }

    public UnreadableCiphertextException(String message, Throwable cause) {
        super(message, cause);
    }
}
