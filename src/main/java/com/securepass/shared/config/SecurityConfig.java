package com.securepass.shared.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securepass.modules.identity.domain.Role;
import com.securepass.shared.security.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final int BCRYPT_COST = 12;

    private final List<String> allowedOrigins;

    public SecurityConfig(
            @Value("${securepass.cors.allowed-origins:https://admin.securepass.app}") String allowedOriginsCsv) {
        this.allowedOrigins = Arrays.stream(allowedOriginsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter, ObjectMapper mapper)
            throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                // Sin sesion valida: 401, no el 403 por default de Spring. Los
                // clientes refrescan el access token solo ante un 401.
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, e) -> {
                    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Autenticacion requerida");
                    pd.setType(URI.create("https://securepass.app/errors/invalid-credentials"));
                    pd.setTitle("invalid-credentials");
                    pd.setInstance(URI.create(request.getRequestURI()));
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                    mapper.writeValue(response.getOutputStream(), pd);
                }))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // Un 403 de URL redirige a /error sin el filtro JWT: sin esto
                        // el cliente veria 401 y refrescaria en vez de mostrar el 403.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/v1/auth/**", "/api/v1/activation/**", "/activar/**",
                                "/actuator/health/**", "/actuator/info",
                                "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        // Metricas revelan rutas, latencias y estado del pool: solo plataforma.
                        .requestMatchers("/actuator/**").hasRole(Role.PLATFORM_ADMIN.name())
                        .anyRequest().authenticated());
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        // spec 5.5: CORS restringido al dominio del admin web.
        // Configurable via SECUREPASS_CORS_ALLOWED_ORIGINS (csv).
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(allowedOrigins);
        cfg.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        // X-Neighborhood-Id: el duenio de la plataforma ve un barrio (#258).
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Device-Id", "X-Neighborhood-Id"));
        cfg.setExposedHeaders(List.of("Retry-After", "Content-Disposition"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_COST);
    }
}
