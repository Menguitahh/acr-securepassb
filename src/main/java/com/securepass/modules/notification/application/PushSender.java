package com.securepass.modules.notification.application;

import java.time.Instant;
import java.util.UUID;

public interface PushSender {

    /**
     * Envia un push al residente notificando que un invitado suyo esta
     * proximo a exceder la permanencia. La implementacion produccion habla
     * con Expo por HTTPS; en dev/tests un adapter simplemente registra la
     * intencion (US-41 solo exige "envio registrado").
     */
    void notifyStayWarning(UUID residentUserId, StayWarning payload);

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
}
