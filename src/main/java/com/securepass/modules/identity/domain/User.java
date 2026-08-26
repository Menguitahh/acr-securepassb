package com.securepass.modules.identity.domain;

import java.util.UUID;
import java.util.regex.Pattern;

public record User(
        UUID id,
        String email,
        String phone,
        String passwordHash,
        String fullName,
        String documentNumber,
        UserStatus status
) {
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int FULL_NAME_MAX = 140;
    private static final int EMAIL_MAX = 160;
    private static final int PHONE_MAX = 30;
    private static final int DOCUMENT_MAX = 20;

    public User {
        if (id == null) throw new IllegalArgumentException("id requerido");
        if (status == null) throw new IllegalArgumentException("status requerido");
        // v2 WBS-128: PENDING_ACTIVATION arranca con password_hash NULL. Login
        // rechaza !ACTIVE, y activateWithPassword lo llena al confirmar el token.
        boolean pending = status == UserStatus.PENDING_ACTIVATION;
        if (!pending && (passwordHash == null || passwordHash.isBlank())) {
            throw new IllegalArgumentException("passwordHash requerido salvo en PENDING_ACTIVATION");
        }
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("fullName requerido");
        }
        if (fullName.length() > FULL_NAME_MAX) {
            throw new IllegalArgumentException("fullName excede " + FULL_NAME_MAX);
        }
        boolean noEmail = email == null || email.isBlank();
        boolean noPhone = phone == null || phone.isBlank();
        if (noEmail && noPhone) {
            throw new IllegalArgumentException("email o phone requerido");
        }
        if (!noEmail) {
            if (email.length() > EMAIL_MAX) throw new IllegalArgumentException("email excede " + EMAIL_MAX);
            if (!EMAIL.matcher(email).matches()) throw new IllegalArgumentException("email invalido");
        }
        if (!noPhone && phone.length() > PHONE_MAX) {
            throw new IllegalArgumentException("phone excede " + PHONE_MAX);
        }
        if (documentNumber != null && documentNumber.length() > DOCUMENT_MAX) {
            throw new IllegalArgumentException("documentNumber excede " + DOCUMENT_MAX);
        }
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }
}
