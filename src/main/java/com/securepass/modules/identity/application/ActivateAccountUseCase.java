package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.ActivationToken;
import com.securepass.modules.identity.domain.TokenPurpose;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Activa la cuenta consumiendo el token de activacion. La atomicidad del
 * "un solo uso" descansa en {@link ActivationTokenRepository#markConsumed}
 * (UPDATE con WHERE consumed_at IS NULL): solo el primer POST gana.
 */
@Service
public class ActivateAccountUseCase {

    static final int MIN_PASSWORD_LENGTH = 8;

    private final ActivationTokenRepository tokens;
    private final UserRepository users;
    private final PasswordEncoder encoder;

    public ActivateAccountUseCase(
            ActivationTokenRepository tokens,
            UserRepository users,
            PasswordEncoder encoder) {
        this.tokens = tokens;
        this.users = users;
        this.encoder = encoder;
    }

    @Transactional
    public Result activate(String plaintextToken, String newPassword) {
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) return Failure.WEAK_PASSWORD;
        if (plaintextToken == null || plaintextToken.isBlank()) return Failure.GONE;

        byte[] hash;
        try {
            hash = ActivationTokenGenerator.hash(plaintextToken);
        } catch (IllegalArgumentException e) {
            return Failure.GONE;
        }
        Optional<ActivationToken> found = tokens.findByHash(hash);
        if (found.isEmpty()) return Failure.GONE;
        ActivationToken token = found.get();
        if (token.purpose() != TokenPurpose.ACTIVATION) return Failure.GONE;
        if (!token.isUsable(Instant.now())) return Failure.GONE;

        if (!tokens.markConsumed(token.id())) return Failure.GONE;

        boolean activated = users.activateWithPassword(token.userId(), encoder.encode(newPassword));
        if (!activated) return Failure.GONE;

        return Success.INSTANCE;
    }

    public sealed interface Result permits Success, Failure {}

    public enum Success implements Result { INSTANCE }

    public enum Failure implements Result {
        GONE,
        WEAK_PASSWORD
    }
}
