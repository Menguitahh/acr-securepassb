package com.securepass.modules.invitation.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Base64;
import java.util.Optional;

@Component
public class QrTokenCodec {

    public static final String PREFIX = "SP1.";

    private final ObjectMapper mapper;

    public QrTokenCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(QrTokenPayload payload, byte[] signature) {
        byte[] payloadBytes = serialize(payload);
        return PREFIX + b64url(payloadBytes) + "." + b64url(signature);
    }

    public byte[] serialize(QrTokenPayload payload) {
        try {
            return mapper.writeValueAsBytes(payload);
        } catch (IOException e) {
            throw new IllegalStateException("no se pudo serializar el payload", e);
        }
    }

    public Optional<ParsedQrToken> parse(String token) {
        if (token == null || !token.startsWith(PREFIX)) return Optional.empty();
        String body = token.substring(PREFIX.length());
        String[] parts = body.split("\\.");
        if (parts.length != 2) return Optional.empty();
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[0]);
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            QrTokenPayload payload = mapper.readValue(payloadBytes, QrTokenPayload.class);
            return Optional.of(new ParsedQrToken(payload, payloadBytes, signature));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String b64url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}
