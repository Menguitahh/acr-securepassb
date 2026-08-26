package com.securepass.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securepass.modules.identity.domain.Role;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceParseTest {

    private static final String SECRET = "test-secret-that-is-long-enough-to-pass-hmac-32-byte-check";

    private final JwtService jwt = new JwtService(SECRET, 900, new ObjectMapper());

    @Test
    void roundTripDevuelveClaims() {
        UUID user = UUID.randomUUID();
        UUID nid = UUID.randomUUID();
        UUID lot = UUID.randomUUID();
        var issued = jwt.issue(user, nid, List.of(Role.GUARD, Role.ADMIN), List.of(lot));

        Optional<JwtService.Claims> parsed = jwt.parse(issued.token());

        assertThat(parsed).isPresent();
        JwtService.Claims claims = parsed.get();
        assertThat(claims.sub()).isEqualTo(user);
        assertThat(claims.neighborhoodId()).isEqualTo(nid);
        assertThat(claims.roles()).containsExactly(Role.GUARD, Role.ADMIN);
        assertThat(claims.lots()).containsExactly(lot);
    }

    @Test
    void tokenTampeadoNoValida() {
        var issued = jwt.issue(UUID.randomUUID(), null, List.of(), List.of());
        String tampered = issued.token().substring(0, issued.token().length() - 4) + "AAAA";

        assertThat(jwt.parse(tampered)).isEmpty();
    }

    @Test
    void tokenExpiradoNoValida() {
        JwtService shortLived = new JwtService(SECRET, 900, new ObjectMapper());
        var issued = shortLived.issue(UUID.randomUUID(), null, List.of(), List.of());
        JwtService otherKey = new JwtService("otro-secreto-de-al-menos-32-bytes-para-que-inicialice", 900, new ObjectMapper());

        assertThat(otherKey.parse(issued.token())).isEmpty();
    }

    @Test
    void tokenMalFormadoNoValida() {
        assertThat(jwt.parse("no-es-un-jwt")).isEmpty();
        assertThat(jwt.parse("a.b")).isEmpty();
        assertThat(jwt.parse(null)).isEmpty();
        assertThat(jwt.parse("")).isEmpty();
    }
}
