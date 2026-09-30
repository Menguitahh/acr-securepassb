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
     *   <li>{@link SubjectKind#GUEST}: {@code invitationId}, {@code lotCode} y
     *       {@code subjectName} obligatorios; {@code userId} debe ser null.</li>
     *   <li>{@link SubjectKind#WORKER}: {@code userId} y {@code subjectName}
     *       obligatorios; {@code invitationId} y {@code lotCode} deben ser
     *       null. El lote se decide en el proximo scan (validate computa la
     *       franja); el enrolamiento pasa una sola vez por persona.</li>
     * </ul>
     *
     * <p>Issue #173: {@code subjectName} viaja en ambas ramas. Revierte la
     * omision original de WBS-122, que asumia que retenerlo evitaba que el
     * guardia comparara el documento contra la pantalla en vez de contra el
     * dato. La premisa era falsa: el backend nunca compara nombres. La unica
     * verificacion de identidad en la garita es visual del guardia contra el
     * documento fisico. Sin el nombre en NEEDS_ENROLLMENT, la pantalla de
     * confirmacion muestra solo el numero de DNI recien escaneado — un tap
     * ritual contra si mismo. Con el nombre, el guardia hace el cruce
     * ("invitacion dice PEREZ, documento dice PEREZ") en el unico momento en
     * que ese cruce sirve: antes de confirmar el enroll.
     */
    record NeedsEnrollment(SubjectKind kind, UUID invitationId, UUID userId, String subjectName, String lotCode)
            implements AccessDecision {
        public NeedsEnrollment {
            if (kind == null) throw new IllegalArgumentException("kind requerido");
            if (subjectName == null || subjectName.isBlank())
                throw new IllegalArgumentException("subjectName requerido");
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

        public static NeedsEnrollment guest(UUID invitationId, String guestName, String lotCode) {
            return new NeedsEnrollment(SubjectKind.GUEST, invitationId, null, guestName, lotCode);
        }

        public static NeedsEnrollment worker(UUID userId, String workerName) {
            return new NeedsEnrollment(SubjectKind.WORKER, null, userId, workerName, null);
        }
    }

    /**
     * #217: trabajador con mas de un lote en franja y sin lote elegido. Online
     * no se persiste: la garita muestra {@code lots} y reenvia con el lotId
     * elegido y un clientEventId nuevo (mismo patron que NEEDS_ENROLLMENT).
     */
    record NeedsLotSelection(UUID userId, String workerName, List<LotOption> lots) implements AccessDecision {
        public NeedsLotSelection {
            if (userId == null) throw new IllegalArgumentException("userId requerido");
            if (workerName == null || workerName.isBlank()) throw new IllegalArgumentException("workerName requerido");
            if (lots == null || lots.size() < 2) throw new IllegalArgumentException("lots >= 2");
            lots = List.copyOf(lots);
        }
    }

    /**
     * #210: {@code invitationId} es el pase que se presento, cuando la firma
     * verifico y la invitacion es del barrio del guardia. Null si no hay pase
     * confiable (firma invalida, invitacion inexistente o de otro barrio).
     * Vincula el log al pase; no dice quien lo presento.
     */
    record Denied(DenialReason reason, UUID invitationId) implements AccessDecision {
        public Denied {
            if (reason == null) throw new IllegalArgumentException("reason requerido");
        }

        public static Denied of(DenialReason reason) {
            return new Denied(reason, null);
        }

        public static Denied of(DenialReason reason, UUID invitationId) {
            return new Denied(reason, invitationId);
        }
    }
}
