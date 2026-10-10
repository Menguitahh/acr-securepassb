package com.securepass.shared.security;

import com.securepass.modules.identity.domain.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwt;

    public JwtAuthenticationFilter(JwtTokenProvider jwt) {
        this.jwt = jwt;
    }

    static final String VIEW_HEADER = "X-Neighborhood-Id";
    private static final java.util.Set<String> READ_METHODS = java.util.Set.of("GET", "HEAD", "OPTIONS");
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void reject(HttpServletResponse response, int status, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        String title = status == HttpServletResponse.SC_FORBIDDEN ? "forbidden" : "validation-error";
        response.getWriter().write("{\"type\":\"https://securepass.app/errors/" + title + "\",\"title\":\""
                + title + "\",\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        Optional<JwtTokenProvider.Claims> parsed = jwt.validateToken(header.substring(BEARER_PREFIX.length()));
        if (parsed.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        JwtTokenProvider.Claims claims = parsed.get();
        UUID neighborhoodId = claims.neighborhoodId();
        List<Role> roles = claims.roles();

        // securepassb#258: el duenio de la plataforma ve un barrio mandando su
        // id en X-Neighborhood-Id. Ve lo mismo que el admin de ese barrio, en
        // modo lectura: cualquier escritura con el header se rechaza aca.
        String viewing = request.getHeader(VIEW_HEADER);
        if (viewing != null && roles.contains(Role.PLATFORM_ADMIN)) {
            UUID target = parseUuid(viewing);
            if (target == null) {
                reject(response, HttpServletResponse.SC_BAD_REQUEST, "X-Neighborhood-Id invalido");
                return;
            }
            if (!READ_METHODS.contains(request.getMethod())) {
                reject(response, HttpServletResponse.SC_FORBIDDEN,
                        "Modo lectura: el duenio de la plataforma no modifica datos de un barrio");
                return;
            }
            LOG.info("platform.view user={} neighborhood={} {} {}",
                    claims.sub(), target, request.getMethod(), request.getRequestURI());
            neighborhoodId = target;
            roles = java.util.stream.Stream.concat(roles.stream(), java.util.stream.Stream.of(Role.ADMIN))
                    .distinct().toList();
        }

        CurrentUser user = new CurrentUser(claims.sub(), neighborhoodId, roles, claims.lots());
        List<SimpleGrantedAuthority> authorities = roles.stream()
                .map(r -> new SimpleGrantedAuthority(r.authority()))
                .toList();
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(user, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
    }
}
