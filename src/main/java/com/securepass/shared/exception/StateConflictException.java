package com.securepass.shared.exception;

public class StateConflictException extends RuntimeException {
    public StateConflictException(String detail) {
        super(detail);
    }
}
