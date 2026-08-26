package com.securepass.modules.invitation.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
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
        List<Integer> weekdays,
        LocalTime dailyFrom,
        LocalTime dailyUntil,
        int maxUses,
        int usedCount,
        String tokenKid,
        UUID jti,
        boolean residentPass,
        Instant createdAt
) {
    private static final int SINGLE_EVENT_MAX_DAYS = 30;
    private static final int RECURRING_MAX_DAYS = 365;
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

        if (!residentPass) {
            int maxDays = type == InvitationType.RECURRING ? RECURRING_MAX_DAYS : SINGLE_EVENT_MAX_DAYS;
            if (Duration.between(validFrom, validUntil).toDays() > maxDays) {
                throw new IllegalArgumentException("BR-04: ventana excede " + maxDays + " dias para " + type);
            }
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
        if (type == InvitationType.RECURRING) {
            if (weekdays == null || weekdays.isEmpty()) {
                throw new IllegalArgumentException("RECURRING requiere weekdays");
            }
            for (Integer d : weekdays) {
                if (d == null || d < 1 || d > 7) throw new IllegalArgumentException("weekdays entre 1 y 7");
            }
            if (dailyFrom == null || dailyUntil == null) {
                throw new IllegalArgumentException("RECURRING requiere dailyFrom y dailyUntil");
            }
            if (!dailyUntil.isAfter(dailyFrom)) {
                throw new IllegalArgumentException("dailyUntil debe ser posterior a dailyFrom");
            }
        }
        if (residentPass && type != InvitationType.RECURRING) {
            throw new IllegalArgumentException("residentPass requiere type RECURRING");
        }
        if (tokenKid == null || tokenKid.isBlank()) throw new IllegalArgumentException("tokenKid requerido");
        if (tokenKid.length() > TOKEN_KID_MAX) throw new IllegalArgumentException("tokenKid excede " + TOKEN_KID_MAX);
        if (jti == null) throw new IllegalArgumentException("jti requerido");
        if (createdAt == null) throw new IllegalArgumentException("createdAt requerido");

        weekdays = weekdays == null ? null : List.copyOf(weekdays);
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
                validFrom, validUntil, weekdays, dailyFrom, dailyUntil,
                maxUses, newUsedCount, tokenKid, jti, residentPass, createdAt);
    }
}
