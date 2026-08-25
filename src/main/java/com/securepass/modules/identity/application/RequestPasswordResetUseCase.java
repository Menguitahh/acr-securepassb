package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.TokenPurpose;
import com.securepass.modules.identity.domain.User;
import com.securepass.modules.identity.domain.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

/**
 * US-53: emite un token de recuperacion si el email pertenece a un usuario
 * ACTIVE. La respuesta HTTP es identica exista o no la cuenta (no
 * enumeracion de usuarios): esta clase decide en silencio si emitir el
 * token o no hacer nada. El caller SIEMPRE devuelve el mismo 204.
 */
@Service
public class RequestPasswordResetUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(RequestPasswordResetUseCase.class);

    private final UserRepository users;
    private final IssueActivationTokenUseCase issuer;

    public RequestPasswordResetUseCase(UserRepository users, IssueActivationTokenUseCase issuer) {
        this.users = users;
        this.issuer = issuer;
    }

    public void request(String email) {
        if (email == null || email.isBlank()) return;
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        Optional<User> user = users.findByEmail(normalized);
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
