package com.securepass.shared.exception;

import java.util.UUID;

/**
 * 409 al alta de usuario. Codigo distinguible para que el frontend ramifique:
 *
 *   EMAIL_TAKEN               — email ya existe con status ACTIVE o SUSPENDED
 *   PENDING_ACTIVATION_EXISTS — email ya existe en PENDING_ACTIVATION
 *                               (existingUserId presente; el admin puede
 *                               reenviar la activacion)
 *   DNI_TAKEN                 — DNI ya existe (existingUserId presente;
 *                               el admin puede vincular con POST
 *                               /admin/lots/{id}/members en vez de recrear)
 */
public class UserOnboardingConflictException extends RuntimeException {

    private final String code;
    private final UUID existingUserId;

    public UserOnboardingConflictException(String code, UUID existingUserId, String detail) {
        super(detail);
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code requerido");
        this.code = code;
        this.existingUserId = existingUserId;
    }

    public String code() {
        return code;
    }

    public UUID existingUserId() {
        return existingUserId;
    }
}
