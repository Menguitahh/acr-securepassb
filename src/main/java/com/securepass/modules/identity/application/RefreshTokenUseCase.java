package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.identity.domain.User;
import com.securepass.shared.security.JwtService;
import com.securepass.shared.security.RefreshTokenGenerator;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RefreshTokenUseCase {

    private final RefreshTokenStore store;
    private final UserRepository users;
    private final UserProfileQuery profiles;
    private final JwtService jwt;
    private final RefreshTokenGenerator generator;

    public RefreshTokenUseCase(
            RefreshTokenStore store,
            UserRepository users,
            UserProfileQuery profiles,
            JwtService jwt,
            RefreshTokenGenerator generator) {
        this.store = store;
        this.users = users;
        this.profiles = profiles;
        this.jwt = jwt;
        this.generator = generator;
    }

    public Result refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) return Failure.INVALID_REFRESH;

        RefreshTokenStore.ConsumeResult consume = store.consume(rawRefreshToken);
        if (consume instanceof RefreshTokenStore.ReplayDetected replay) {
            store.revokeFamily(replay.familyId());
            return Failure.INVALID_REFRESH;
        }
        if (!(consume instanceof RefreshTokenStore.Consumed consumed)) return Failure.INVALID_REFRESH;

        Optional<User> found = users.findById(consumed.userId());
        if (found.isEmpty() || !found.get().isActive()) {
            store.revokeFamily(consumed.familyId());
            return Failure.INVALID_REFRESH;
        }

        User user = found.get();
        UserProfileSummary summary = profiles.summarize(user.id());
        List<UUID> lotIds = summary.lots().stream().map(LotSummary::id).toList();
        JwtService.IssuedAccessToken access = jwt.issue(user.id(), summary.neighborhoodId(), summary.roles(), lotIds);
        String newRefresh = generator.generate();
        store.save(newRefresh, consumed.familyId(), consumed.userId());
        return new Success(
                access.token(),
                newRefresh,
                access.expiresIn(),
                user.id(),
                user.fullName(),
                summary.neighborhoodId(),
                summary.neighborhoodTimezone(),
                summary.roles(),
                summary.lots());
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
        INVALID_REFRESH
    }
}
