package com.securepass.shared.exception;

public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("credenciales invalidas");
    }
}
