package com.securepass.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securepass.modules.identity.domain.Role;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderValidateTest {

    private static final String SECRET = "test-secret-that-is-long-enough-to-pass-hmac-32-byte-check";

    private final JwtTokenProvider jwt = new JwtTokenProvider(SECRET, 900, new ObjectMapper());

    @Test
    void roundTripDevuelveClaims() {
        UUID user = UUID.randomUUID();
        UUID nid = UUID.randomUUID();
        UUID lot = UUID.randomUUID();
        var issued = jwt.issue(user, nid, List.of(Role.GUARD, Role.ADMIN), List.of(lot));

        Optional<JwtTokenProvider.Claims> parsed = jwt.validateToken(issued.token());

        assertThat(parsed).isPresent();
        JwtTokenProvider.Claims claims = parsed.get();
        assertThat(claims.sub()).isEqualTo(user);
        assertThat(claims.neighborhoodId()).isEqualTo(nid);
        assertThat(claims.roles()).containsExactly(Role.GUARD, Role.ADMIN);
        assertThat(claims.lots()).containsExactly(lot);
    }

    @Test
    void tokenTampeadoNoValida() {
        var issued = jwt.issue(UUID.randomUUID(), null, List.of(), List.of());
        String tampered = issued.token().substring(0, issued.token().length() - 4) + "AAAA";

        assertThat(jwt.validateToken(tampered)).isEmpty();
    }

    @Test
    void tokenExpiradoNoValida() {
        JwtTokenProvider shortLived = new JwtTokenProvider(SECRET, 900, new ObjectMapper());
        var issued = shortLived.issue(UUID.randomUUID(), null, List.of(), List.of());
        JwtTokenProvider otherKey = new JwtTokenProvider("otro-secreto-de-al-menos-32-bytes-para-que-inicialice", 900, new ObjectMapper());

        assertThat(otherKey.validateToken(issued.token())).isEmpty();
    }

    @Test
    void tokenMalFormadoNoValida() {
        assertThat(jwt.validateToken("no-es-un-jwt")).isEmpty();
        assertThat(jwt.validateToken("a.b")).isEmpty();
        assertThat(jwt.validateToken(null)).isEmpty();
        assertThat(jwt.validateToken("")).isEmpty();
    }
}
