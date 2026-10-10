package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.NeighborhoodNameQuery;
import com.securepass.modules.identity.application.ResendLotActivationUseCase;
import com.securepass.modules.identity.application.UserRepository;
import com.securepass.modules.identity.domain.User;
import com.securepass.shared.exception.RateLimitExceededException;
import com.securepass.shared.ratelimit.RateLimiter;
import com.securepass.shared.security.AccessGuard;
import com.securepass.shared.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** US-54: reenvio de activacion desde el lote (familiares y trabajadores). */
@RestController
@RequestMapping("/api/v1/lots/{lotId}/members/{userId}/resend-activation")
public class LotActivationController {

    // Mismo limite y misma clave que el reenvio del admin: 3 por hora por persona.
    private static final int RESEND_LIMIT = 3;
    private static final Duration RESEND_WINDOW = Duration.ofHours(1);

    private final ResendLotActivationUseCase useCase;
    private final UserRepository users;
    private final NeighborhoodNameQuery neighborhoods;
    private final AccessGuard guard;
    private final RateLimiter limiter;

    public LotActivationController(ResendLotActivationUseCase useCase, UserRepository users,
                                   NeighborhoodNameQuery neighborhoods, AccessGuard guard, RateLimiter limiter) {
        this.useCase = useCase;
        this.users = users;
        this.neighborhoods = neighborhoods;
        this.guard = guard;
        this.limiter = limiter;
    }

    @PostMapping
    @PreAuthorize("@lotPerm.hasAtLeast(#lotId, T(com.securepass.shared.security.LotPermissionType).OWNER)")
    public ResponseEntity<Void> resend(@PathVariable UUID lotId, @PathVariable UUID userId) {
        CurrentUser actor = guard.require();
        Optional<Duration> retry = limiter.tryAcquire("resend-activation:" + userId, RESEND_LIMIT, RESEND_WINDOW);
        if (retry.isPresent()) throw new RateLimitExceededException(Duration.ofSeconds(Math.max(1, (retry.get().toMillis() + 999) / 1000)));
        String actorName = users.findById(actor.userId()).map(User::fullName).orElse("Titular");
        useCase.resend(lotId, userId, actorName, neighborhoods.findName(actor.neighborhoodId()));
        return ResponseEntity.noContent().build();
    }
}
