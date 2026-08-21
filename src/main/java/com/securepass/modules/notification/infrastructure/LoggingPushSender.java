package com.securepass.modules.notification.infrastructure;

import com.securepass.modules.notification.application.PushSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * ponytail: adapter minimo que registra la intencion de push en el log. El
 * envio real por Expo queda para cuando exista el pipeline de credenciales
 * de push del entorno productivo. US-41 solo exige "envio registrado" y el
 * `stays.resident_notified_at` cumple ese contrato.
 */
@Component
public class LoggingPushSender implements PushSender {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingPushSender.class);

    @Override
    public void notifyStayWarning(UUID residentUserId, StayWarning payload) {
        LOG.info("push.stay-warning resident={} stay={} subject={} entered={} minutesRemaining={}",
                residentUserId, payload.stayId(), payload.subjectName(),
                payload.enteredAt(), payload.minutesRemaining());
    }
}
