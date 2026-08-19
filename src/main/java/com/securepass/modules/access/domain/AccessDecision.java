package com.securepass.modules.access.domain;

import java.util.UUID;

public sealed interface AccessDecision {

    record Allowed(UUID invitationId, UUID jti, String guestName, String lotCode, int companions)
            implements AccessDecision {
        public Allowed {
            if (invitationId == null) throw new IllegalArgumentException("invitationId requerido");
            if (jti == null) throw new IllegalArgumentException("jti requerido");
            if (guestName == null || guestName.isBlank()) throw new IllegalArgumentException("guestName requerido");
            if (lotCode == null || lotCode.isBlank()) throw new IllegalArgumentException("lotCode requerido");
            if (companions < 0) throw new IllegalArgumentException("companions >= 0");
        }
    }

    record Denied(DenialReason reason) implements AccessDecision {
        public Denied {
            if (reason == null) throw new IllegalArgumentException("reason requerido");
        }

        public static Denied of(DenialReason reason) {
            return new Denied(reason);
        }
    }
}
