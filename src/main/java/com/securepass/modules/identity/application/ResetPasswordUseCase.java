package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.ActivationToken;
import com.securepass.modules.identity.domain.TokenPurpose;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * WBS-103: consume un token PASSWORD_RESET y reemplaza la contrasena del
 * usuario ACTIVE asociado. Mismo patron de atomicidad que
 * {@link ActivateAccountUseCase}: markConsumed cierra la carrera.
 */
@Service
public class ResetPasswordUseCase {

    private final ActivationTokenRepository tokens;
    private final UserRepository users;
    private final PasswordEncoder encoder;

    public ResetPasswordUseCase(ActivationTokenRepository tokens, UserRepository users, PasswordEncoder encoder) {
        this.tokens = tokens;
        this.users = users;
        this.encoder = encoder;
    }

    @Transactional
    public Result reset(String plaintextToken, String newPassword) {
        if (newPassword == null || newPassword.length() < ActivateAccountUseCase.MIN_PASSWORD_LENGTH) {
            return Failure.WEAK_PASSWORD;
        }
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
        if (token.purpose() != TokenPurpose.PASSWORD_RESET) return Failure.GONE;
        if (!token.isUsable(Instant.now())) return Failure.GONE;

        if (!tokens.markConsumed(token.id())) return Failure.GONE;
        if (!users.updatePassword(token.userId(), encoder.encode(newPassword))) return Failure.GONE;

        return Success.INSTANCE;
    }

    public sealed interface Result permits Success, Failure {}

    public enum Success implements Result { INSTANCE }

    public enum Failure implements Result {
        GONE,
        WEAK_PASSWORD
    }
}
