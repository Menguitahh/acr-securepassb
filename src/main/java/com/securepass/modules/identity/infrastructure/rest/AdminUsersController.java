package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.NeighborhoodNameQuery;
import com.securepass.modules.identity.application.OnboardUserUseCase;
import com.securepass.modules.identity.application.ResendActivationUseCase;
import com.securepass.modules.identity.application.UserRepository;
import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.identity.domain.User;
import com.securepass.shared.exception.RateLimitExceededException;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.ratelimit.RateLimiter;
import com.securepass.shared.security.AccessGuard;
import com.securepass.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUsersController {

    // Alineado con forgot-password: 3 pedidos por hora por usuario objetivo.
    static final int RESEND_LIMIT = 3;
    static final Duration RESEND_WINDOW = Duration.ofHours(1);

    private final OnboardUserUseCase onboard;
    private final ResendActivationUseCase resend;
    private final UserRepository users;
    private final NeighborhoodNameQuery neighborhoods;
    private final AccessGuard guard;
    private final RateLimiter limiter;

    public AdminUsersController(
            OnboardUserUseCase onboard,
            ResendActivationUseCase resend,
            UserRepository users,
            NeighborhoodNameQuery neighborhoods,
            AccessGuard guard,
            RateLimiter limiter) {
        this.onboard = onboard;
        this.resend = resend;
        this.users = users;
        this.neighborhoods = neighborhoods;
        this.guard = guard;
        this.limiter = limiter;
    }

    @PostMapping
    public ResponseEntity<CreatedUserResponse> create(@Valid @RequestBody CreateUserRequest req) {
        CurrentUser admin = guard.require();
        Role role = parseRole(req.role());
        assertRoleLotShape(role, req.lotId());

        OnboardUserUseCase.Command cmd = new OnboardUserUseCase.Command(
                req.email(), req.fullName(), req.dni(), role, req.lotId());
        UUID newUserId = onboard.onboard(
                admin.neighborhoodId(),
                actorName(admin),
                neighborhoods.findName(admin.neighborhoodId()),
                cmd);

        return ResponseEntity.created(URI.create("/api/v1/admin/users/" + newUserId))
                .body(new CreatedUserResponse(newUserId, role.name(), "PENDING_ACTIVATION"));
    }

    @PostMapping("/{id}/resend-activation")
    public ResponseEntity<Void> resendActivation(@PathVariable UUID id) {
        CurrentUser admin = guard.require();

        Optional<Duration> retry = limiter.tryAcquire(
                "resend-activation:" + id, RESEND_LIMIT, RESEND_WINDOW);
        if (retry.isPresent()) throw new RateLimitExceededException(ceilSeconds(retry.get()));

        resend.resend(id, actorName(admin), neighborhoods.findName(admin.neighborhoodId()));
        return ResponseEntity.noContent().build();
    }

    private String actorName(CurrentUser admin) {
        return users.findById(admin.userId()).map(User::fullName).orElse("Admin");
    }

    private static Role parseRole(String raw) {
        try {
            return Role.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "role invalido; permitidos: RESIDENT_OWNER, GUARD");
        }
    }

    private static void assertRoleLotShape(Role role, UUID lotId) {
        if (role != Role.RESIDENT_OWNER && role != Role.GUARD) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "role invalido; permitidos: RESIDENT_OWNER, GUARD");
        }
        if (role == Role.RESIDENT_OWNER && lotId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "lotId requerido para RESIDENT_OWNER");
        }
        if (role == Role.GUARD && lotId != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "lotId no permitido para GUARD");
        }
    }

    private static Duration ceilSeconds(Duration d) {
        long secs = d.toMillis() / 1000;
        if (d.toMillis() % 1000 != 0) secs++;
        if (secs < 1) secs = 1;
        return Duration.ofSeconds(secs);
    }

    public record CreateUserRequest(
            @NotBlank @Email String email,
            @NotBlank String fullName,
            @NotBlank @Pattern(regexp = "\\d{7,8}", message = "dni de 7 u 8 digitos") String dni,
            @NotBlank String role,
            UUID lotId
    ) {}

    public record CreatedUserResponse(UUID userId, String role, String status) {}
}
