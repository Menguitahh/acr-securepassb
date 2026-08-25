package com.securepass.modules.identity.infrastructure.email;

import com.securepass.modules.identity.application.ActivationTokenIssuedEvent;
import com.securepass.modules.identity.domain.TokenPurpose;
import com.securepass.modules.notification.application.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * WBS-102: escucha la emision de tokens de activacion y recuperacion,
 * arma el link y despacha el email. AFTER_COMMIT para no enviar si la
 * transaccion se revierte; @Async("emailExecutor") para no bloquear el
 * hilo del request.
 */
@Component
public class ActivationEmailListener {

    private static final Logger LOG = LoggerFactory.getLogger(ActivationEmailListener.class);

    private final EmailSender emailSender;
    private final TemplateRenderer renderer;
    private final String activationBaseUrl;

    public ActivationEmailListener(
            EmailSender emailSender,
            TemplateRenderer renderer,
            @Value("${securepass.activation.base-url:http://localhost:8080}") String activationBaseUrl) {
        this.emailSender = emailSender;
        this.renderer = renderer;
        this.activationBaseUrl = trimTrailingSlash(activationBaseUrl);
    }

    @Async("emailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTokenIssued(ActivationTokenIssuedEvent event) {
        try {
            // Unificamos el path para ambos propositos: la app resuelve el
            // proposito via GET /activation/{token} y rutea segun purpose.
            // El fallback web /activar/{token} sirve la misma pagina "abrir
            // en la app" para los dos casos.
            String url = activationBaseUrl + "/activar/" + URLEncoder.encode(event.plaintextToken(), StandardCharsets.UTF_8);
            String template = event.purpose() == TokenPurpose.PASSWORD_RESET ? "password-reset.txt" : "activation.txt";
            String subject = subjectFor(event);

            Map<String, String> vars = new HashMap<>();
            vars.put("fullName", event.fullName());
            vars.put("url", url);
            vars.put("validity", validityLabel(Instant.now(), event.expiresAt()));
            vars.put("invitedByLine", invitedByLine(event));

            String body = renderer.render(template, vars);
            emailSender.send(new EmailSender.EmailMessage(event.email(), subject, null, body));
        } catch (RuntimeException ex) {
            LOG.warn("activation.email.failed user={} purpose={} error={}",
                    event.userId(), event.purpose(), ex.getMessage());
        }
    }

    private String subjectFor(ActivationTokenIssuedEvent event) {
        if (event.purpose() == TokenPurpose.PASSWORD_RESET) return "Recuperar tu contraseña en Securepass";
        if (event.neighborhoodName() != null && !event.neighborhoodName().isBlank()) {
            return "Activá tu cuenta en " + event.neighborhoodName();
        }
        return "Activá tu cuenta en Securepass";
    }

    private String invitedByLine(ActivationTokenIssuedEvent event) {
        boolean hasWho = event.invitedByName() != null && !event.invitedByName().isBlank();
        boolean hasWhere = event.neighborhoodName() != null && !event.neighborhoodName().isBlank();
        if (hasWho && hasWhere) return event.invitedByName() + " te dio de alta en " + event.neighborhoodName() + ".";
        if (hasWhere) return "Fuiste dado de alta en " + event.neighborhoodName() + ".";
        if (hasWho) return event.invitedByName() + " te dio de alta.";
        return "";
    }

    private static String validityLabel(Instant from, Instant to) {
        long seconds = Math.max(1, Duration.between(from, to).getSeconds());
        long dayS = 86_400L;
        long hourS = 3_600L;
        if (seconds >= dayS) {
            long days = (seconds + dayS - 1) / dayS;
            return days == 1 ? "1 día" : days + " días";
        }
        long hours = (seconds + hourS - 1) / hourS;
        return hours == 1 ? "1 hora" : hours + " horas";
    }

    private static String trimTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
