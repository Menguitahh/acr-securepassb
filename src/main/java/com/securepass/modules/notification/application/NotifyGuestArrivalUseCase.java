package com.securepass.modules.notification.application;

import com.securepass.modules.access.application.GuestArrivedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * WBS-43 / US-25: al ingreso ALLOWED de un invitado dispara un push al
 * residente que creo la invitacion.
 *
 * Se ejecuta AFTER_COMMIT (no revierte el `access_log` si el push falla,
 * spec 5.5) y `@Async("pushExecutor")` para no bloquear el hilo del
 * request: `/access/validate` tiene objetivo de p95 < 200 ms y la
 * llamada a Expo puede colgarse hasta el timeout. Ver
 * {@link com.securepass.modules.notification.infrastructure.NotificationAsyncConfig}.
 */
@Service
public class NotifyGuestArrivalUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(NotifyGuestArrivalUseCase.class);

    private final PushSender push;

    public NotifyGuestArrivalUseCase(PushSender push) {
        this.push = push;
    }

    @Async("pushExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGuestArrived(GuestArrivedEvent event) {
        try {
            push.notifyGuestArrival(event.residentUserId(), new PushSender.GuestArrival(
                    event.stayId(),
                    event.subjectName(),
                    event.enteredAt(),
                    event.lotCode(),
                    event.syncedOffline()));
        } catch (RuntimeException ex) {
            // Fallo de push no revierte el ingreso (ya commiteado); solo se loguea.
            LOG.warn("push.guest-arrival.failed stay={} resident={} error={}",
                    event.stayId(), event.residentUserId(), ex.getMessage());
        }
    }
}
