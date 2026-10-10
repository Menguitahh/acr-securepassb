package com.securepass.shared.exception;

import java.util.UUID;

/**
 * 409 al alta de usuario. Codigo distinguible para que el frontend ramifique:
 *
 *   EMAIL_TAKEN               — email ya existe (con DNI distinto), status
 *                               ACTIVE o PENDING_ACTIVATION.
 *   USER_SUSPENDED            — WBS-107: el DNI corresponde a un user con
 *                               status SUSPENDED. No se suma permiso; el
 *                               admin del barrio nuevo debe coordinar con
 *                               quien lo suspendio antes de darle acceso.
 *                               (existingUserId presente para trazabilidad).
 *   USER_DELETED              — WBS-107: el DNI corresponde a un user
 *                               DELETED. No se reactiva silenciosamente
 *                               (puede ser baja voluntaria o por incidente).
 *
 * Nota: pre-WBS-107 existian tambien DNI_TAKEN y PENDING_ACTIVATION_EXISTS;
 * fueron reemplazados por el flujo de reuso: el alta con DNI existente ya
 * no falla, devuelve 200 con reused=true.
 */
public class UserOnboardingConflictException extends RuntimeException {

    private final String code;
    private final UUID existingUserId;
    private final SuspensionDetail suspension;

    public UserOnboardingConflictException(String code, UUID existingUserId, String detail) {
        this(code, existingUserId, detail, null);
    }

    public UserOnboardingConflictException(String code, UUID existingUserId, String detail,
                                           SuspensionDetail suspension) {
        super(detail);
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code requerido");
        this.code = code;
        this.existingUserId = existingUserId;
        this.suspension = suspension;
    }

    /** securepassb#121: en USER_SUSPENDED del alta del admin, que barrio la puso y por que. */
    public SuspensionDetail suspension() {
        return suspension;
    }

    /** Campos null en una suspension anterior a #121. ownNeighborhood: la puso el barrio del admin. */
    public record SuspensionDetail(String neighborhoodName, String reason, java.time.Instant suspendedAt,
                                   boolean ownNeighborhood) {}

    public String code() {
        return code;
    }

    public UUID existingUserId() {
        return existingUserId;
    }
}
