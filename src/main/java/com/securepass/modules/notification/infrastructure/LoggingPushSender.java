package com.securepass.modules.notification.infrastructure;

import com.securepass.modules.notification.application.PushSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Adapter por defecto (dev/tests): registra la intencion en el log y no
 * habla con el servicio Expo. Se activa cuando `securepass.push.provider`
 * es `logging` o no esta definido; produccion setea `expo` y toma
 * {@link ExpoPushSender}.
 */
@Component
@ConditionalOnProperty(name = "securepass.push.provider", havingValue = "logging", matchIfMissing = true)
public class LoggingPushSender implements PushSender {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingPushSender.class);

    @Override
    public void notifyStayWarning(UUID residentUserId, StayWarning payload) {
        // Sin PII (spec 5.5): subjectName queda fuera del log.
        LOG.info("push.stay-warning resident={} stay={} entered={} minutesRemaining={}",
                residentUserId, payload.stayId(),
                payload.enteredAt(), payload.minutesRemaining());
    }

    @Override
    public void notifyGuestArrival(UUID residentUserId, GuestArrival payload) {
        LOG.info("push.guest-arrival resident={} stay={} entered={} lot={} syncedOffline={}",
                residentUserId, payload.stayId(),
                payload.enteredAt(), payload.lotCode(), payload.syncedOffline());
    }

    @Override
    public void notifyGuardOfResidentDecision(UUID guardUserId, GuardResidentDecision payload) {
        // Sin PII (spec 5.5): subjectName / residentName quedan fuera del log.
        LOG.info("push.resident-decision guard={} stay={} invitation={} kind={} expectedUntil={}",
                guardUserId, payload.stayId(), payload.invitationId(),
                payload.kind(), payload.expectedUntil());
    }
}
