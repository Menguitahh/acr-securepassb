package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.LoginUseCase;
import com.securepass.modules.identity.application.LogoutUseCase;
import com.securepass.modules.identity.application.RefreshTokenUseCase;
import com.securepass.shared.exception.InvalidCredentialsException;
import com.securepass.shared.exception.RateLimitExceededException;
import com.securepass.shared.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    // spec 5.4: 5 intentos por (IP + email) cada 15 minutos.
    static final int LOGIN_LIMIT = 5;
    static final Duration LOGIN_WINDOW = Duration.ofMinutes(15);

    private final LoginUseCase loginUseCase;
    private final RefreshTokenUseCase refreshUseCase;
    private final LogoutUseCase logoutUseCase;
    private final RateLimiter limiter;

    public AuthController(
            LoginUseCase loginUseCase,
            RefreshTokenUseCase refreshUseCase,
            LogoutUseCase logoutUseCase,
            RateLimiter limiter) {
        this.loginUseCase = loginUseCase;
        this.refreshUseCase = refreshUseCase;
        this.logoutUseCase = logoutUseCase;
        this.limiter = limiter;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        String key = "login:" + clientIp(http) + ":" + request.identifier().toLowerCase(Locale.ROOT);
        Optional<Duration> retry = limiter.tryAcquire(key, LOGIN_LIMIT, LOGIN_WINDOW);
        if (retry.isPresent()) throw new RateLimitExceededException(ceilSeconds(retry.get()));

        LoginUseCase.Result result = loginUseCase.login(request.identifier(), request.password());
        if (result instanceof LoginUseCase.Success success) return LoginResponse.from(success);
        throw new InvalidCredentialsException();
    }

    @PostMapping("/refresh")
    public RefreshResponse refresh(@Valid @RequestBody RefreshRequest request) {
        RefreshTokenUseCase.Result result = refreshUseCase.refresh(request.refreshToken());
        if (result instanceof RefreshTokenUseCase.Success success) return RefreshResponse.from(success);
        throw new InvalidCredentialsException();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        logoutUseCase.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

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
