package com.securepass.shared.ratelimit;

import com.securepass.shared.exception.RateLimitExceededException;
import com.securepass.shared.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Aplica los limites de spec.md 5.4 sobre endpoints que no dependen del body.
 * Login queda fuera: usa IP + email y se resuelve en el controller cuando
 * los dos valores estan disponibles.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    static final int INVITATIONS_PER_HOUR = 30;
    static final int VALIDATE_PER_MIN = 120;
    static final int SYNC_PER_MIN = 10;

    private final RateLimiter limiter;
    private final List<Rule> rules;

    public RateLimitInterceptor(RateLimiter limiter) {
        this.limiter = limiter;
        this.rules = List.of(
                new Rule(HttpMethod.POST, "/api/v1/invitations", RateLimitInterceptor::userKey,
                        INVITATIONS_PER_HOUR, Duration.ofHours(1)),
                new Rule(HttpMethod.POST, "/api/v1/access/validate", RateLimitInterceptor::deviceKey,
                        VALIDATE_PER_MIN, Duration.ofMinutes(1)),
                new Rule(HttpMethod.POST, "/api/v1/access/sync", RateLimitInterceptor::deviceKey,
                        SYNC_PER_MIN, Duration.ofMinutes(1))
        );
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        for (Rule rule : rules) {
            if (!rule.method.matches(method)) continue;
            if (!path.equals(rule.path)) continue;
            String key = rule.keyFn.apply(request);
            if (key == null) continue; // sin usuario/dispositivo aun -> deja pasar y que 401/403 responda
            Optional<Duration> retry = limiter.tryAcquire(rule.name() + ":" + key, rule.limit, rule.window);
            if (retry.isPresent()) {
                throw new RateLimitExceededException(ceilSeconds(retry.get()));
            }
            return true;
        }
        return true;
    }

    private static Duration ceilSeconds(Duration d) {
        long secs = d.toMillis() / 1000;
        if (d.toMillis() % 1000 != 0) secs++;
        if (secs < 1) secs = 1;
        return Duration.ofSeconds(secs);
    }

    private static String userKey(HttpServletRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        if (auth.getPrincipal() instanceof CurrentUser user) return user.userId().toString();
        return null;
    }

    private static String deviceKey(HttpServletRequest req) {
        String device = req.getHeader("X-Device-Id");
        if (device != null && !device.isBlank()) return "d:" + device;
        return "ip:" + clientIp(req);
    }

    private static String clientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return req.getRemoteAddr();
    }

    private record Rule(HttpMethod method, String path, java.util.function.Function<HttpServletRequest, String> keyFn,
                        int limit, Duration window) {
        String name() {
            return method.name() + ":" + path;
        }
    }
}
