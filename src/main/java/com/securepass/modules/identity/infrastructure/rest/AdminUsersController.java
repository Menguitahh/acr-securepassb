package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.NeighborhoodNameQuery;
import com.securepass.modules.identity.application.OnboardUserUseCase;
import com.securepass.modules.identity.application.ResendActivationUseCase;
import com.securepass.modules.identity.application.SuspendUserUseCase;
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
import lombok.AllArgsConstructor;
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

@AllArgsConstructor
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
    private final SuspendUserUseCase suspension;

    // securepassb#121: un pedido de revision por persona y barrio cada 24 h.
    static final int REVIEW_LIMIT = 1;
    static final Duration REVIEW_WINDOW = Duration.ofHours(24);

    // securepassb#121: buscar a una persona del barrio para suspenderla.
    @org.springframework.web.bind.annotation.GetMapping
    public java.util.List<PersonResponse> search(
            @org.springframework.web.bind.annotation.RequestParam("q")
            @jakarta.validation.constraints.Size(min = 2, max = 80) String q) {
        CurrentUser admin = guard.require();
        return users.searchInNeighborhood(admin.neighborhoodId(), q).stream()
                .map(p -> PersonResponse.from(p, admin.neighborhoodId())).toList();
    }

    @PostMapping("/{id}/suspend")
    public ResponseEntity<Void> suspend(@PathVariable UUID id, @Valid @RequestBody SuspendRequest req) {
        suspension.suspend(guard.require(), id, req.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/lift-suspension")
    public ResponseEntity<Void> liftSuspension(@PathVariable UUID id) {
        suspension.lift(guard.require(), id);
        return ResponseEntity.noContent().build();
    }

    // securepassb#121: el admin de otro barrio pide revisar la suspension; el
    // id sale del 409 USER_SUSPENDED del alta.
    @PostMapping("/{id}/suspension-review")
    public ResponseEntity<Void> requestSuspensionReview(@PathVariable UUID id,
                                                        @Valid @RequestBody SuspensionReviewRequest req) {
        CurrentUser admin = guard.require();
        Optional<Duration> retry = limiter.tryAcquire(
                "suspension-review:" + admin.neighborhoodId() + ":" + id, REVIEW_LIMIT, REVIEW_WINDOW);
        if (retry.isPresent()) throw new RateLimitExceededException(ceilSeconds(retry.get()));
        User me = users.findById(admin.userId()).orElseThrow();
        suspension.requestReview(admin, me.fullName(), me.email(),
                neighborhoods.findName(admin.neighborhoodId()), id, req.message());
        return ResponseEntity.accepted().build();
    }

    public record SuspendRequest(@NotBlank @jakarta.validation.constraints.Size(min = 3, max = 280) String reason) {}

    public record SuspensionReviewRequest(@jakarta.validation.constraints.Size(max = 500) String message) {}

    @PostMapping
    public ResponseEntity<OnboardUserResponse> create(@Valid @RequestBody CreateUserRequest req) {
        CurrentUser admin = guard.require();
        Role role = parseRole(req.role());
        assertRoleLotShape(role, req.lotId());

        OnboardUserUseCase.Command cmd = new OnboardUserUseCase.Command(
                req.email(), req.fullName(), req.dni(), role, req.lotId());
        OnboardUserUseCase.Result result = onboard.onboard(
                admin.neighborhoodId(),
                actorName(admin),
                neighborhoods.findName(admin.neighborhoodId()),
                cmd);

        OnboardUserResponse body = new OnboardUserResponse(
                result.userId(), result.role().name(), result.status().name(),
                result.reused(), result.changed(), result.email(), result.fullName());

        // 201 solo cuando creamos user; 200 para reuso (nada nuevo en `users`).
        if (result.reused()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/admin/users/" + result.userId())).body(body);
    }

    // US-54: cuentas del barrio que todavia no se activaron, para reenviar.
    @org.springframework.web.bind.annotation.GetMapping("/pending")
    public java.util.List<PendingActivationResponse> pending() {
        CurrentUser admin = guard.require();
        return users.pendingInNeighborhood(admin.neighborhoodId()).stream()
                .map(PendingActivationResponse::from).toList();
    }

    @PostMapping("/{id}/resend-activation")
    public ResponseEntity<Void> resendActivation(@PathVariable UUID id) {
        CurrentUser admin = guard.require();
        // US-54: solo cuentas del barrio del admin. Antes cualquier admin podia
        // disparar el mail de una cuenta de otro barrio.
        if (!users.belongsToNeighborhood(id, admin.neighborhoodId())) {
            throw new com.securepass.shared.exception.ResourceNotFoundException("user", id);
        }

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

    /**
     * WBS-107: shape unificado creacion vs reuso. `reused=true` significa
     * que el DNI ya existia y devolvemos el user existente en vez de 409;
     * `changed=false` significa que ademas el permiso ya estaba (idempotente).
     * `email` y `fullName` son los del user real (pueden diferir del pasado);
     * el frontend TIENE que mostrarlos cuando reused=true para que el admin
     * detecte divergencia de nombre o email.
     */
    public record OnboardUserResponse(UUID userId, String role, String status,
                                      boolean reused, boolean changed,
                                      String email, String fullName) {}
}
