package com.securepass.modules.notification.application;

import java.time.Instant;
import java.util.UUID;

public interface PushSender {

    /**
     * Envia un push al residente notificando que un invitado suyo esta
     * proximo a exceder la permanencia (US-41).
     */
    void notifyStayWarning(UUID residentUserId, StayWarning payload);

    /**
     * Envia un push al residente al ingreso ALLOWED de su invitado (US-25).
     * Para eventos sincronizados offline se usa la hora real de ingreso
     * (`enteredAt`), no la del sync.
     */
    void notifyGuestArrival(UUID residentUserId, GuestArrival payload);

    /**
     * WBS-72 / US-42: push informativo al guardia con turno abierto en la
     * garita del ingreso, cuando el residente decide extender el pase o
     * pedir que la visita se retire. La pantalla Presentes de la garita
     * sigue siendo la fuente de verdad; este push solo avisa que algo
     * cambio y para quien, para que el guardia mire.
     */
    void notifyGuardOfResidentDecision(UUID guardUserId, GuardResidentDecision payload);

    /**
     * securepassf#33: {@code invitationId} viaja en el push para que las
     * acciones de la notificacion (extender / que se retire) llamen a sus
     * endpoints sin abrir otra pantalla.
     */
    record StayWarning(
            UUID stayId,
            UUID invitationId,
            String subjectName,
            Instant enteredAt,
            long minutesRemaining
    ) {
        public StayWarning {
            if (stayId == null) throw new IllegalArgumentException("stayId requerido");
            if (invitationId == null) throw new IllegalArgumentException("invitationId requerido");
            if (subjectName == null || subjectName.isBlank()) throw new IllegalArgumentException("subjectName requerido");
            if (enteredAt == null) throw new IllegalArgumentException("enteredAt requerido");
        }
    }

    record GuestArrival(
            UUID stayId,
            String subjectName,
            Instant enteredAt,
            String lotCode,
            boolean syncedOffline
    ) {
        public GuestArrival {
            if (stayId == null) throw new IllegalArgumentException("stayId requerido");
            if (subjectName == null || subjectName.isBlank()) throw new IllegalArgumentException("subjectName requerido");
            if (enteredAt == null) throw new IllegalArgumentException("enteredAt requerido");
        }
    }

    record GuardResidentDecision(
            UUID stayId,
            UUID invitationId,
            Kind kind,
            String subjectName,   // invitado
            String residentName,  // residente que decidio
            Instant expectedUntil // usado solo para EXTENDED en el body
    ) {
        public GuardResidentDecision {
            if (stayId == null) throw new IllegalArgumentException("stayId requerido");
            if (invitationId == null) throw new IllegalArgumentException("invitationId requerido");
            if (kind == null) throw new IllegalArgumentException("kind requerido");
            if (subjectName == null || subjectName.isBlank()) throw new IllegalArgumentException("subjectName requerido");
            if (residentName == null || residentName.isBlank()) throw new IllegalArgumentException("residentName requerido");
            if (expectedUntil == null) throw new IllegalArgumentException("expectedUntil requerido");
        }

        public enum Kind { EXTENDED, EXIT_REQUESTED }
    }
}
