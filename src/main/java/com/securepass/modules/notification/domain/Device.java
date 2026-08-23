package com.securepass.modules.notification.domain;

import java.time.Instant;
import java.util.UUID;

public record Device(
        UUID id,
        UUID userId,
        String expoPushToken,
        Platform platform,
        Instant lastSeenAt,
        Instant createdAt
) {
    public Device {
        if (id == null) throw new IllegalArgumentException("id requerido");
        if (userId == null) throw new IllegalArgumentException("userId requerido");
        if (expoPushToken == null || expoPushToken.isBlank()) {
            throw new IllegalArgumentException("expoPushToken requerido");
        }
        // Expo push tokens tienen formato ExponentPushToken[...] o ExpoPushToken[...].
        // La validacion estricta la hace el servicio Expo; aca solo bloqueamos payloads
        // basura que ni siquiera podrian ser un token.
        if (expoPushToken.length() < 10 || expoPushToken.length() > 200) {
            throw new IllegalArgumentException("expoPushToken longitud invalida");
        }
        if (platform == null) throw new IllegalArgumentException("platform requerida");
        if (lastSeenAt == null) throw new IllegalArgumentException("lastSeenAt requerido");
        if (createdAt == null) throw new IllegalArgumentException("createdAt requerido");
    }

    public enum Platform { IOS, ANDROID }
}
