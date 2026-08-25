package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.ActivationToken;
import com.securepass.modules.identity.domain.TokenPurpose;
import com.securepass.modules.identity.domain.User;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
public class LookupActivationUseCase {

    private final ActivationTokenRepository tokens;
    private final UserRepository users;

    public LookupActivationUseCase(ActivationTokenRepository tokens, UserRepository users) {
        this.tokens = tokens;
        this.users = users;
    }

    public Result lookup(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return Failure.GONE;
        byte[] hash;
        try {
            hash = ActivationTokenGenerator.hash(plaintext);
        } catch (IllegalArgumentException e) {
            return Failure.GONE;
        }
        Optional<ActivationToken> found = tokens.findByHash(hash);
        if (found.isEmpty()) return Failure.GONE;
        ActivationToken token = found.get();
        if (!token.isUsable(Instant.now())) return Failure.GONE;
        Optional<User> user = users.findById(token.userId());
        if (user.isEmpty()) return Failure.GONE;
        return new Valid(user.get().email(), user.get().fullName(), token.purpose(), token.expiresAt());
    }

    public sealed interface Result permits Valid, Failure {}

    public record Valid(String email, String fullName, TokenPurpose purpose, Instant expiresAt) implements Result {}

    public enum Failure implements Result {
        GONE
    }
}
