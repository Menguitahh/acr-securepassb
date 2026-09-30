package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.identity.domain.User;
import com.securepass.shared.security.JwtService;
import com.securepass.shared.security.RefreshTokenGenerator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class LoginUseCase {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final RefreshTokenGenerator refresh;
    private final UserProfileQuery profiles;
    private final RefreshTokenStore refreshStore;

    public LoginUseCase(
            UserRepository users,
            PasswordEncoder encoder,
            JwtService jwt,
            RefreshTokenGenerator refresh,
            UserProfileQuery profiles,
            RefreshTokenStore refreshStore) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.refresh = refresh;
        this.profiles = profiles;
        this.refreshStore = refreshStore;
    }

    public Result login(String identifier, String rawPassword) {
        if (identifier == null || identifier.isBlank()) return Failure.INVALID_CREDENTIALS;
        if (rawPassword == null || rawPassword.isEmpty()) return Failure.INVALID_CREDENTIALS;

        Optional<User> found = users.findByIdentifier(identifier.trim());
        if (found.isEmpty()) return Failure.INVALID_CREDENTIALS;

        User user = found.get();
        if (!user.isActive()) return Failure.INVALID_CREDENTIALS;
        if (user.passwordHash() == null || user.passwordHash().isBlank()) return Failure.INVALID_CREDENTIALS;
        if (!encoder.matches(rawPassword, user.passwordHash())) return Failure.INVALID_CREDENTIALS;

        UserProfileSummary summary = profiles.summarize(user.id());
        List<UUID> lotIds = summary.residentLotIds();
        JwtService.IssuedAccessToken access = jwt.issue(user.id(), summary.neighborhoodId(), summary.roles(), lotIds);
        String refreshToken = refresh.generate();
        UUID familyId = UUID.randomUUID();
        refreshStore.save(refreshToken, familyId, user.id());

        return new Success(
                access.token(),
                refreshToken,
                access.expiresIn(),
                user.id(),
                user.fullName(),
                summary.neighborhoodId(),
                summary.neighborhoodTimezone(),
                summary.roles(),
                summary.lots()
        );
    }

    public sealed interface Result permits Success, Failure {}

    public record Success(
            String accessToken,
            String refreshToken,
            long expiresIn,
            UUID userId,
            String fullName,
            UUID neighborhoodId,
            String neighborhoodTimezone,
            List<Role> roles,
            List<LotSummary> lots
    ) implements Result {}

    public enum Failure implements Result {
        INVALID_CREDENTIALS
    }
}
