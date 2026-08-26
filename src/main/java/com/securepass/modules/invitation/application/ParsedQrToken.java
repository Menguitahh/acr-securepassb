package com.securepass.modules.invitation.application;

public record ParsedQrToken(QrTokenPayload payload, byte[] payloadBytes, byte[] signature) {
    public ParsedQrToken {
        if (payload == null) throw new IllegalArgumentException("payload requerido");
        if (payloadBytes == null || payloadBytes.length == 0) throw new IllegalArgumentException("payloadBytes requerido");
        if (signature == null || signature.length == 0) throw new IllegalArgumentException("signature requerido");
    }
}
