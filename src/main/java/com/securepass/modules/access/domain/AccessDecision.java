package com.securepass.modules.access.domain;

import java.util.List;
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

    /**
     * WBS-117 / US-67: worker autorizado por al menos un permiso WORKER activo
     * dentro de franja horaria. Si {@code lots.size()==1}, la garita entra
     * directo; si >1, la app le pide al guardia tocar el correcto (spec §L).
     */
    record AllowedWorker(UUID userId, UUID jti, String workerName, List<LotOption> lots)
            implements AccessDecision {
        public AllowedWorker {
            if (userId == null) throw new IllegalArgumentException("userId requerido");
            if (jti == null) throw new IllegalArgumentException("jti requerido");
            if (workerName == null || workerName.isBlank()) throw new IllegalArgumentException("workerName requerido");
            if (lots == null || lots.isEmpty()) throw new IllegalArgumentException("lots requerido");
            lots = List.copyOf(lots);
        }
    }

    record LotOption(UUID lotId, String lotCode, String service) {
        public LotOption {
            if (lotId == null) throw new IllegalArgumentException("lotId requerido");
            if (lotCode == null || lotCode.isBlank()) throw new IllegalArgumentException("lotCode requerido");
            if (service == null || service.isBlank()) throw new IllegalArgumentException("service requerido");
        }
    }

    // No lleva guestName: la identidad no esta verificada aun. Si el guardia
    // ve el nombre antes de pedir el DNI, compara el documento contra la
    // pantalla en vez de que el sistema compare a ciegas. La verificacion
    // tiene que ser sistema contra dato; el nombre aparece en el enroll.
    record NeedsEnrollment(UUID invitationId, String lotCode) implements AccessDecision {
        public NeedsEnrollment {
            if (invitationId == null) throw new IllegalArgumentException("invitationId requerido");
            if (lotCode == null || lotCode.isBlank()) throw new IllegalArgumentException("lotCode requerido");
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
