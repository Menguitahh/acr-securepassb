package com.securepass.shared.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securepass.modules.identity.domain.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtService {

    private static final int MIN_SECRET_BYTES = 32;

    private final byte[] secret;
    private final long ttlSeconds;
    private final ObjectMapper mapper;

    public JwtService(
            @Value("${securepass.jwt.secret}") String secret,
            @Value("${securepass.jwt.ttl-seconds:900}") long ttlSeconds,
            ObjectMapper mapper) {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("securepass.jwt.secret debe tener al menos " + MIN_SECRET_BYTES + " bytes");
        }
        if (ttlSeconds <= 0) throw new IllegalStateException("securepass.jwt.ttl-seconds debe ser > 0");
        this.secret = bytes;
        this.ttlSeconds = ttlSeconds;
        this.mapper = mapper;
    }

    public IssuedAccessToken issue(UUID userId, UUID neighborhoodId, List<Role> roles, List<UUID> lotIds) {
        if (userId == null) throw new IllegalArgumentException("userId requerido");
        long iat = Instant.now().getEpochSecond();
        long exp = iat + ttlSeconds;
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", userId.toString());
        if (neighborhoodId != null) claims.put("nid", neighborhoodId.toString());
        claims.put("roles", roles == null ? List.of() : roles.stream().map(Enum::name).toList());
        claims.put("lots", lotIds == null ? List.of() : lotIds.stream().map(UUID::toString).toList());
        claims.put("iat", iat);
        claims.put("exp", exp);
        try {
            String header = b64(mapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
            String payload = b64(mapper.writeValueAsBytes(claims));
            String signing = header + "." + payload;
            String signature = b64(hmac(signing.getBytes(StandardCharsets.UTF_8)));
            return new IssuedAccessToken(signing + "." + signature, ttlSeconds);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("no se pudo serializar el JWT", e);
        }
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 no disponible", e);
        }
    }

    private static String b64(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    public record IssuedAccessToken(String token, long expiresIn) {}
}
