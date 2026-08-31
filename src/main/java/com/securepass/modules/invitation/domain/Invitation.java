package com.securepass.modules.invitation.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record Invitation(
        UUID id,
        UUID neighborhoodId,
        UUID lotId,
        UUID createdBy,
        InvitationType type,
        InvitationStatus status,
        String guestName,
        String guestDocument,
        int companions,
        String notes,
        Instant validFrom,
        Instant validUntil,
        int maxUses,
        int usedCount,
        String tokenKid,
        UUID jti,
        boolean residentPass,
        Instant createdAt
) {
    private static final int SINGLE_EVENT_MAX_DAYS = 30;
    private static final int EVENT_MAX_USES = 200;
    private static final int COMPANIONS_MAX = 20;
    private static final int GUEST_NAME_MAX = 140;
    private static final int GUEST_DOCUMENT_MAX = 20;
    private static final java.util.regex.Pattern GUEST_DOCUMENT_FORMAT =
            java.util.regex.Pattern.compile("^[0-9]{7,8}$");
    private static final int NOTES_MAX = 280;
    private static final int TOKEN_KID_MAX = 16;

    public Invitation {
        if (id == null) throw new IllegalArgumentException("id requerido");
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        if (lotId == null) throw new IllegalArgumentException("lotId requerido");
        if (createdBy == null) throw new IllegalArgumentException("createdBy requerido");
        if (type == null) throw new IllegalArgumentException("type requerido");
        if (status == null) throw new IllegalArgumentException("status requerido");
        if (guestName == null || guestName.isBlank()) throw new IllegalArgumentException("guestName requerido");
        if (guestName.length() > GUEST_NAME_MAX) throw new IllegalArgumentException("guestName excede " + GUEST_NAME_MAX);
        if (guestDocument == null || guestDocument.isBlank()) {
            throw new IllegalArgumentException("BR-11: guestDocument requerido");
        }
        if (guestDocument.length() > GUEST_DOCUMENT_MAX) {
            throw new IllegalArgumentException("guestDocument excede " + GUEST_DOCUMENT_MAX);
        }
        if (!GUEST_DOCUMENT_FORMAT.matcher(guestDocument).matches()) {
            throw new IllegalArgumentException("BR-11: guestDocument formato invalido (7-8 digitos)");
        }
        if (companions < 0 || companions > COMPANIONS_MAX) {
            throw new IllegalArgumentException("companions fuera de rango 0..20");
        }
        if (notes != null && notes.length() > NOTES_MAX) {
            throw new IllegalArgumentException("notes excede " + NOTES_MAX);
        }
        if (validFrom == null) throw new IllegalArgumentException("validFrom requerido");
        if (validUntil == null) throw new IllegalArgumentException("validUntil requerido");
        if (!validUntil.isAfter(validFrom)) throw new IllegalArgumentException("validUntil debe ser posterior a validFrom");

        // WBS-127: solo SINGLE/EVENT. RECURRING desaparecio (spec §4.2, BR-04,
        // BR-10) — los trabajadores lo reemplazan. residentPass sigue exento
        // de BR-04: su ventana es FAR_FUTURE por diseno (pase permanente
        // ligado al vinculo del residente con el lote).
        //
        // BR-04 solo se evalua sobre invitaciones ACTIVE. Es regla de creacion
        // (spec: "ventana <= N dias al emitir"); al rehidratar una fila cerrada
        // (REVOKED / USED / EXPIRED) el pasado es lo que fue, no se re-audita.
        // Sin esta guarda, la migracion V17 tendria que clampear el valid_until
        // de RECURRING historicas y perder el dato original visible en el
        // detalle/listado (mostrado en /invitations y /invitations/{id}).
        if (!residentPass && status == InvitationStatus.ACTIVE
                && Duration.between(validFrom, validUntil).toDays() > SINGLE_EVENT_MAX_DAYS) {
            throw new IllegalArgumentException("BR-04: ventana excede " + SINGLE_EVENT_MAX_DAYS + " dias para " + type);
        }

        if (maxUses < 1) throw new IllegalArgumentException("maxUses debe ser >= 1");
        if (usedCount < 0) throw new IllegalArgumentException("usedCount debe ser >= 0");
        if (usedCount > maxUses) throw new IllegalArgumentException("usedCount no puede exceder maxUses");

        if (type == InvitationType.SINGLE && maxUses != 1) {
            throw new IllegalArgumentException("SINGLE requiere maxUses = 1");
        }
        if (type == InvitationType.EVENT && maxUses > EVENT_MAX_USES) {
            throw new IllegalArgumentException("BR-05: EVENT maxUses <= " + EVENT_MAX_USES);
        }
        if (tokenKid == null || tokenKid.isBlank()) throw new IllegalArgumentException("tokenKid requerido");
        if (tokenKid.length() > TOKEN_KID_MAX) throw new IllegalArgumentException("tokenKid excede " + TOKEN_KID_MAX);
        if (jti == null) throw new IllegalArgumentException("jti requerido");
        if (createdAt == null) throw new IllegalArgumentException("createdAt requerido");
    }

    public Invitation registerEntry() {
        if (status != InvitationStatus.ACTIVE) {
            throw new IllegalStateException("solo ACTIVE puede registrar uso, estado actual: " + status);
        }
        if (residentPass) return this;
        int next = usedCount + 1;
        InvitationStatus nextStatus = next == maxUses ? InvitationStatus.USED : InvitationStatus.ACTIVE;
        return copyWith(nextStatus, next);
    }

    public Invitation revoke() {
        if (status == InvitationStatus.REVOKED) return this;
        if (status == InvitationStatus.EXPIRED) {
            throw new IllegalStateException("EXPIRED no puede revocarse");
        }
        return copyWith(InvitationStatus.REVOKED, usedCount);
    }

    public Invitation expire() {
        if (status == InvitationStatus.EXPIRED) return this;
        if (status == InvitationStatus.REVOKED) {
            throw new IllegalStateException("REVOKED no puede expirar");
        }
        return copyWith(InvitationStatus.EXPIRED, usedCount);
    }

    private Invitation copyWith(InvitationStatus newStatus, int newUsedCount) {
        return new Invitation(
                id, neighborhoodId, lotId, createdBy, type, newStatus,
                guestName, guestDocument, companions, notes,
                validFrom, validUntil,
                maxUses, newUsedCount, tokenKid, jti, residentPass, createdAt);
    }
}
