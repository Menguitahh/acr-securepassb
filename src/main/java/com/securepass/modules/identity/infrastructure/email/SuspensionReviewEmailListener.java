package com.securepass.modules.identity.infrastructure.email;

import com.securepass.modules.identity.application.SuspensionReviewRequestedEvent;
import com.securepass.modules.notification.application.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * securepassb#121: avisa a los admins del barrio que suspendio que otro
 * barrio pide revisar la suspension. Responden por email a quien la pidio.
 */
@Component
public class SuspensionReviewEmailListener {

    private static final Logger LOG = LoggerFactory.getLogger(SuspensionReviewEmailListener.class);

    private final EmailSender emailSender;
    private final TemplateRenderer renderer;

    public SuspensionReviewEmailListener(EmailSender emailSender, TemplateRenderer renderer) {
        this.emailSender = emailSender;
        this.renderer = renderer;
    }

    @Async("emailExecutor")
    @EventListener
    public void onReviewRequested(SuspensionReviewRequestedEvent event) {
        String message = event.message() == null || event.message().isBlank()
                ? "" : "Mensaje:\n" + event.message() + "\n";
        String body = renderer.render("suspension-review.txt", Map.of(
                "personName", event.personName(),
                "requesterName", event.requesterName(),
                "requesterNeighborhood", event.requesterNeighborhood() == null ? "otro barrio" : event.requesterNeighborhood(),
                "requesterEmail", event.requesterEmail() == null ? "" : event.requesterEmail(),
                "message", message));
        String subject = "Piden revisar la suspensión de " + event.personName();
        for (String to : event.adminEmails()) {
            try {
                emailSender.send(new EmailSender.EmailMessage(to, subject, null, body));
            } catch (RuntimeException ex) {
                LOG.warn("suspension-review.email.failed user={} error={}", event.userId(), ex.getMessage());
            }
        }
    }
}
