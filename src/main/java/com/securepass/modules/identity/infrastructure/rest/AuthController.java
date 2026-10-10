package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.LoginUseCase;
import com.securepass.modules.identity.application.LogoutUseCase;
import com.securepass.modules.identity.application.RefreshTokenUseCase;
import com.securepass.modules.identity.application.RequestPasswordResetUseCase;
import com.securepass.modules.identity.application.ResetPasswordUseCase;
import com.securepass.shared.exception.BusinessRuleViolationException;
import com.securepass.shared.exception.InvalidCredentialsException;
import com.securepass.shared.exception.RateLimitExceededException;
import com.securepass.shared.exception.TokenGoneException;
import com.securepass.shared.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    // spec 5.4: 5 intentos por (IP + email) cada 15 minutos.
    static final int LOGIN_LIMIT = 5;
    static final Duration LOGIN_WINDOW = Duration.ofMinutes(15);

    // WBS-103 / US-53: 3 pedidos por email por hora. Frena abuso sin
    // castigar al residente que se olvido la contrasena.
    static final int FORGOT_LIMIT = 3;
    static final Duration FORGOT_WINDOW = Duration.ofHours(1);

    // Y 30 pedidos por IP por hora. El limite por email protege al dueno
    // de la casilla; el limite por IP frena que se use el endpoint como
    // relay de spam rotando emails.
    static final int FORGOT_IP_LIMIT = 30;

    private final LoginUseCase loginUseCase;
    private final RefreshTokenUseCase refreshUseCase;
    private final LogoutUseCase logoutUseCase;
    private final RequestPasswordResetUseCase requestReset;
    private final ResetPasswordUseCase resetPassword;
    private final RateLimiter limiter;

    @PostMapping("/login")
    @PreAuthorize("permitAll()")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        String key = "login:" + clientIp(http) + ":" + request.identifier().toLowerCase(Locale.ROOT);
        Optional<Duration> retry = limiter.tryAcquire(key, LOGIN_LIMIT, LOGIN_WINDOW);
        if (retry.isPresent()) throw new RateLimitExceededException(ceilSeconds(retry.get()));

        LoginUseCase.Result result = loginUseCase.login(request.identifier(), request.password());
        if (result instanceof LoginUseCase.Success success) return LoginResponse.from(success);
        throw new InvalidCredentialsException();
    }

    @PostMapping("/refresh")
    @PreAuthorize("permitAll()")
    public RefreshResponse refresh(@Valid @RequestBody RefreshRequest request) {
        RefreshTokenUseCase.Result result = refreshUseCase.refresh(request.refreshToken());
        if (result instanceof RefreshTokenUseCase.Success success) return RefreshResponse.from(success);
        throw new InvalidCredentialsException();
    }

    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        logoutUseCase.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/forgot-password")
    @PreAuthorize("permitAll()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest http) {
        String ip = clientIp(http);
        Optional<Duration> retryIp = limiter.tryAcquire("forgot-ip:" + ip, FORGOT_IP_LIMIT, FORGOT_WINDOW);
        if (retryIp.isPresent()) throw new RateLimitExceededException(ceilSeconds(retryIp.get()));

        String email = request.email().toLowerCase(Locale.ROOT);
        Optional<Duration> retryEmail = limiter.tryAcquire("forgot:" + email, FORGOT_LIMIT, FORGOT_WINDOW);
        if (retryEmail.isPresent()) throw new RateLimitExceededException(ceilSeconds(retryEmail.get()));

        // Silenciamos si el email no corresponde a ningun usuario ACTIVE:
        // devolvemos 204 igual para no revelar quien esta registrado.
        requestReset.request(request.email());
    }

    @PostMapping("/reset-password")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        ResetPasswordUseCase.Result r = resetPassword.reset(request.token(), request.password());
        if (r == ResetPasswordUseCase.Success.INSTANCE) return ResponseEntity.noContent().build();
        if (r == ResetPasswordUseCase.Failure.WEAK_PASSWORD) {
            throw new BusinessRuleViolationException("weak-password", "La contraseña debe tener al menos 8 caracteres");
        }
        throw TokenGoneException.INSTANCE;
    }

    public record ForgotPasswordRequest(@NotBlank @Email String email) {}

    public record ResetPasswordRequest(@NotBlank String token, @NotBlank String password) {}

    private static Duration ceilSeconds(Duration d) {
        long secs = d.toMillis() / 1000;
        if (d.toMillis() % 1000 != 0) secs++;
        if (secs < 1) secs = 1;
        return Duration.ofSeconds(secs);
    }

    private static String clientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}
