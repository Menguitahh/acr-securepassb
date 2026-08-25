package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.ActivationToken;
import com.securepass.modules.identity.domain.TokenPurpose;
import com.securepass.modules.identity.domain.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * WBS-102: emite un token de activacion o recuperacion. La emision es
 * sincronica dentro de la transaccion del caller (consume los anteriores
 * activos y guarda el nuevo, US-54). El envio del email es AFTER_COMMIT
 * en {@code emailExecutor}: si la transaccion se revierte, el email no se
 * envia; si el envio falla, la emision ya esta commiteada.
 */
@Service
public class IssueActivationTokenUseCase {

    private final UserRepository users;
    private final ActivationTokenRepository tokens;
    private final ActivationTokenGenerator generator;
    private final ApplicationEventPublisher events;
    private final Duration activationTtl;
    private final Duration resetTtl;

    public IssueActivationTokenUseCase(
            UserRepository users,
            ActivationTokenRepository tokens,
            ActivationTokenGenerator generator,
            ApplicationEventPublisher events,
            @Value("${securepass.activation.ttl-days:7}") long activationTtlDays,
            @Value("${securepass.password-reset.ttl-hours:2}") long resetTtlHours) {
        this.users = users;
        this.tokens = tokens;
        this.generator = generator;
        this.events = events;
        this.activationTtl = Duration.ofDays(activationTtlDays);
        this.resetTtl = Duration.ofHours(resetTtlHours);
    }

    @Transactional
    public void issue(UUID userId, TokenPurpose purpose, String invitedByName, String neighborhoodName) {
        if (userId == null) throw new IllegalArgumentException("userId requerido");
        if (purpose == null) throw new IllegalArgumentException("purpose requerido");

        User user = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("usuario inexistente: " + userId));
        if (user.email() == null || user.email().isBlank()) {
            throw new IllegalArgumentException("usuario sin email: " + userId);
        }

        tokens.consumeAllActive(userId, purpose);

        ActivationTokenGenerator.Generated g = generator.generate();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttlFor(purpose));
        ActivationToken token = new ActivationToken(
                UUID.randomUUID(), userId, purpose, g.hash(), expiresAt, null, now);
        tokens.save(token);

        events.publishEvent(new ActivationTokenIssuedEvent(
                userId, user.email(), user.fullName(), purpose,
                g.plaintext(), expiresAt, invitedByName, neighborhoodName));
    }

    private Duration ttlFor(TokenPurpose purpose) {
        return purpose == TokenPurpose.PASSWORD_RESET ? resetTtl : activationTtl;
    }
}
