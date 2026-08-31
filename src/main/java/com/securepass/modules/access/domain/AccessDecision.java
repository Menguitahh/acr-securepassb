package com.securepass.modules.access.domain;

import java.util.List;
import java.util.UUID;

public sealed interface AccessDecision {

    /**
     * WBS-122: quien es el sujeto detras de un NEEDS_ENROLLMENT. Determina
     * que campos del record son obligatorios (invariantes del constructor).
     */
    enum SubjectKind { GUEST, WORKER }

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

    /**
     * WBS-122: la verificacion pasa a ser por persona (no por invitacion), y
     * el flujo del trabajador se suma al del invitado. Un mismo record con dos
     * ramas discriminadas por {@code kind}:
     * <ul>
     *   <li>{@link SubjectKind#GUEST}: {@code invitationId} y {@code lotCode}
     *       obligatorios; {@code userId} debe ser null.</li>
     *   <li>{@link SubjectKind#WORKER}: {@code userId} obligatorio;
     *       {@code invitationId} y {@code lotCode} deben ser null. El lote se
     *       decide en el proximo scan (validate computa la franja); el
     *       enrolamiento pasa una sola vez por persona.</li>
     * </ul>
     *
     * <p>No lleva {@code workerName} ni {@code guestName} en ninguna rama: la
     * identidad no esta verificada aun. Si el guardia ve el nombre antes de
     * pedir el DNI, compara el documento contra la pantalla en vez de que el
     * sistema compare a ciegas. La verificacion tiene que ser sistema contra
     * dato; el nombre aparece recien en el enroll.
     */
    record NeedsEnrollment(SubjectKind kind, UUID invitationId, UUID userId, String lotCode)
            implements AccessDecision {
        public NeedsEnrollment {
            if (kind == null) throw new IllegalArgumentException("kind requerido");
            switch (kind) {
                case GUEST -> {
                    if (invitationId == null) throw new IllegalArgumentException("invitationId requerido para GUEST");
                    if (lotCode == null || lotCode.isBlank())
                        throw new IllegalArgumentException("lotCode requerido para GUEST");
                    if (userId != null) throw new IllegalArgumentException("userId no aplica a GUEST");
                }
                case WORKER -> {
                    if (userId == null) throw new IllegalArgumentException("userId requerido para WORKER");
                    if (invitationId != null) throw new IllegalArgumentException("invitationId no aplica a WORKER");
                    if (lotCode != null) throw new IllegalArgumentException("lotCode no aplica a WORKER");
                }
            }
        }

        public static NeedsEnrollment guest(UUID invitationId, String lotCode) {
            return new NeedsEnrollment(SubjectKind.GUEST, invitationId, null, lotCode);
        }

        public static NeedsEnrollment worker(UUID userId) {
            return new NeedsEnrollment(SubjectKind.WORKER, null, userId, null);
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
