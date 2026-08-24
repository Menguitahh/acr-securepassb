package com.securepass.modules.notification.infrastructure;

import com.securepass.modules.notification.application.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Adapter por defecto (dev/tests): escribe el intento a log en vez de
 * enviar. Se activa con {@code securepass.email.provider=logging} (default).
 * Produccion setea {@code resend} y toma {@link ResendEmailSender}.
 */
@Component
@ConditionalOnProperty(name = "securepass.email.provider", havingValue = "logging", matchIfMissing = true)
public class LoggingEmailSender implements EmailSender {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(EmailMessage message) {
        LOG.info("email.send provider=logging to={} subject={}", message.to(), message.subject());
        if (message.textBody() != null) {
            LOG.info("email.body.text ---\n{}\n---", message.textBody());
        }
    }
}
