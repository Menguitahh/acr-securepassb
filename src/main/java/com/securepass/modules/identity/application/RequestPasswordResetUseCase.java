package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.TokenPurpose;
import com.securepass.modules.identity.domain.User;
import com.securepass.modules.identity.domain.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

/**
 * US-53: emite un token de recuperacion si el email pertenece a un usuario
 * ACTIVE. La respuesta HTTP es identica exista o no la cuenta (no
 * enumeracion de usuarios).
 *
 * <p>#114: tampoco el tiempo de respuesta puede delatar si la cuenta existe.
 * El request solo publica el pedido, siempre igual; la busqueda del usuario y
 * la emision del token (INSERT + commit) corren en otro hilo. Ruido con
 * sleep no alcanza: se promedia y la diferencia sistematica queda.
 */
@Service
public class RequestPasswordResetUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(RequestPasswordResetUseCase.class);

    private final UserRepository users;
    private final IssueActivationTokenUseCase issuer;
    private final ApplicationEventPublisher events;

    public RequestPasswordResetUseCase(UserRepository users, IssueActivationTokenUseCase issuer,
                                       ApplicationEventPublisher events) {
        this.users = users;
        this.issuer = issuer;
        this.events = events;
    }

    public record Requested(String email) {}

    public void request(String email) {
        if (email == null || email.isBlank()) return;
        events.publishEvent(new Requested(email.trim().toLowerCase(Locale.ROOT)));
    }

    @Async("emailExecutor")
    @EventListener
    public void onRequested(Requested requested) {
        Optional<User> user = users.findByEmail(requested.email());
        if (user.isEmpty()) {
            LOG.info("password-reset.request email-unknown");
            return;
        }
        if (user.get().status() != UserStatus.ACTIVE) {
            LOG.info("password-reset.request user-not-active status={}", user.get().status());
            return;
        }
        issuer.issue(user.get().id(), TokenPurpose.PASSWORD_RESET, null, null);
    }
}
