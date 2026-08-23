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

    record StayWarning(
            UUID stayId,
            String subjectName,
            Instant enteredAt,
            long minutesRemaining
    ) {
        public StayWarning {
            if (stayId == null) throw new IllegalArgumentException("stayId requerido");
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
}
